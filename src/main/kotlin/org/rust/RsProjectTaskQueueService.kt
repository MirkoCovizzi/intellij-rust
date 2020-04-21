/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.*
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.progress.impl.ProgressManagerImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapiext.isHeadlessEnvironment
import com.intellij.openapiext.isUnitTestMode
import com.intellij.util.PairConsumer
import com.intellij.util.concurrency.QueueProcessor
import org.rust.RsTaskExt.TaskType.*
import org.rust.openapiext.DelayedBackgroundableProcessIndicator
import org.rust.openapiext.checkIsDispatchThread
import java.util.function.BiConsumer

/**
 * A common queue for cargo and macro expansion tasks that should be executed sequentially.
 * Can run any [Task.Backgroundable], but provides additional features for tasks that implement [RsTaskExt].
 * The most important feature is that newly submitted tasks can cancel a currently running task or
 * tasks in the queue (See [RsTaskExt.taskType]).
 */
@Service
class RsProjectTaskQueueService : Disposable {
    private val queue: RsBackgroundTaskQueue = RsBackgroundTaskQueue()

    /** Submits a task. A task can implement [RsTaskExt] */
    fun run(task: Task.Backgroundable) = queue.run(task)

    /** Equivalent to running an empty task with [RsTaskExt.taskType] = [taskType] */
    fun cancelTasks(taskType: RsTaskExt.TaskType) = queue.cancelTasks(taskType)

    /** @return true if no running or pending tasks */
    val isEmpty: Boolean get() = queue.isEmpty

    override fun dispose() {
        queue.dispose()
    }
}

val Project.taskQueue: RsProjectTaskQueueService get() = service()

interface RsTaskExt {
    @JvmDefault
    val taskType: TaskType
        get() = TaskType.INDEPENDENT

    @JvmDefault
    val progressBarShowDelay: Int
        get() = 0

    /** If true, the task will not be run (and progress bar will not be shown) until the smart mode */
    @JvmDefault
    val waitForSmartMode: Boolean
        get() = false

    @JvmDefault
    val runSyncInUnitTests: Boolean
        get() = false

    /**
     * Higher position in the enum means higher priority; Newly submitted tasks with higher or equal
     * priority cancels other tasks with lower or equal priority if [canBeCanceledByOther] == true.
     * E.g. [CARGO_SYNC] cancels [MACROS_UNPROCESSED] and subsequent but not [MACROS_CLEAR] or itself.
     * [MACROS_UNPROCESSED] cancels itself, [MACROS_FULL] and subsequent.
     */
    enum class TaskType(val canBeCanceledByOther: Boolean = true) {
        CARGO_SYNC(canBeCanceledByOther = false),
        MACROS_CLEAR(canBeCanceledByOther = false),
        MACROS_UNPROCESSED,
        MACROS_FULL,
        MACROS_WORKSPACE,

        /** Can't be canceled, cancels nothing. Should be the last variant of the enum. */
        INDEPENDENT(canBeCanceledByOther = false);

        fun canCancelOther(other: TaskType): Boolean =
            other.canBeCanceledByOther && this.ordinal <= other.ordinal
    }
}

/** Inspired by [BackgroundTaskQueue] */
private class RsBackgroundTaskQueue {
    private val processor = QueueProcessor<ContinuableRunnable>(
        QueueConsumer(),
        true,
        QueueProcessor.ThreadToUse.AWT,
        Condition<Any> { isDisposed }
    )

    @Volatile
    private var isDisposed: Boolean = false

    // Guarded by self object monitor (@Synchronized)
    private val cancelableTasks: MutableList<BackgroundableTaskData> = mutableListOf()

    val isEmpty: Boolean get() = processor.isEmpty

    @Synchronized
    fun run(task: Task.Backgroundable) {
        if (isUnitTestMode && task is RsTaskExt && task.runSyncInUnitTests) {
            runTaskInCurrentThread(task)
        } else {
            if (task is RsTaskExt) {
                cancelTasks(task.taskType)
            }
            val data = BackgroundableTaskData(task, ::onFinish)

            // Add to cancelable tasks even if the task is not [RsTaskExt] b/c it still can be canceled by [cancelAll]
            cancelableTasks += data

            processor.add(data)
        }
    }

    private fun runTaskInCurrentThread(task: Task.Backgroundable) {
        check(isUnitTestMode)
        val pm = ProgressManager.getInstance() as ProgressManagerImpl
        pm.runProcessWithProgressInCurrentThread(task, EmptyProgressIndicator(), ModalityState.NON_MODAL)
    }

    @Synchronized
    fun cancelTasks(taskType: RsTaskExt.TaskType) {
        cancelableTasks.removeIf { data ->
            if (data.task is RsTaskExt && taskType.canCancelOther(data.task.taskType)) {
                data.cancel()
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    private fun onFinish(data: BackgroundableTaskData) {
        cancelableTasks.remove(data)
    }

    fun dispose() {
        isDisposed = true
        processor.clear()
        cancelAll()
    }

    @Synchronized
    private fun cancelAll() {
        for (task in cancelableTasks) {
            task.cancel()
        }
        cancelableTasks.clear()
    }

    private interface ContinuableRunnable {
        fun run(continuation: Runnable)
    }

    // BACKCOMPAT: 2019.3. get rid of [PairConsumer] implementation
    private class QueueConsumer : PairConsumer<ContinuableRunnable, Runnable>, BiConsumer<ContinuableRunnable, Runnable> {
        override fun consume(s: ContinuableRunnable, t: Runnable) = accept(s, t)
        override fun accept(t: ContinuableRunnable, u: Runnable) = t.run(u)
    }

    private class BackgroundableTaskData(
        val task: Task.Backgroundable,
        val onFinish: (BackgroundableTaskData) -> Unit
    ) : ContinuableRunnable {

        // Guarded by self object monitor (@Synchronized)
        private var state: State = State.Pending

        @Synchronized
        override fun run(continuation: Runnable) {
            // BackgroundableProcessIndicator should be created from EDT
            checkIsDispatchThread()

            when (state) {
                State.CanceledContinued -> {
                    // continuation is already invoked, do nothing
                    return
                }
                State.Canceled -> {
                    continuation.run()
                    return
                }
                is State.Running -> error("Trying to re-run already running task")
            }

            if (task is RsTaskExt && task.waitForSmartMode && DumbService.getInstance(task.project).isDumb) {
                check(state !is State.WaitForSmartMode)
                state = State.WaitForSmartMode(continuation)
                DumbService.getInstance(task.project).runWhenSmart { run(continuation) }
                return
            }

            val indicator = when {
                isHeadlessEnvironment -> EmptyProgressIndicator()

                task is RsTaskExt && task.progressBarShowDelay > 0 ->
                    DelayedBackgroundableProcessIndicator(task, task.progressBarShowDelay)

                else -> BackgroundableProcessIndicator(task)
            }

            state = State.Running(indicator)

            val pm = ProgressManager.getInstance() as ProgressManagerImpl
            pm.runProcessWithProgressAsynchronously(
                task,
                indicator,
                {
                    onFinish(this)
                    continuation.run()
                },
                ModalityState.NON_MODAL
            )
        }

        @Synchronized
        fun cancel() {
            when (val state = state) {
                State.Pending -> this.state = State.Canceled
                is State.Running -> state.indicator.cancel()
                is State.WaitForSmartMode -> {
                    this.state = State.CanceledContinued
                    state.continuation.run()
                }
                State.Canceled -> Unit
                State.CanceledContinued -> Unit
            }.run {}
        }

        private sealed class State {
            object Pending : State()
            data class WaitForSmartMode(val continuation: Runnable) : State()
            object Canceled : State()
            object CanceledContinued : State()
            data class Running(val indicator: ProgressIndicator) : State()
        }
    }
}
