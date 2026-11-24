package dev.local.sunwindowdemo.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootPermissionTest {
    @Test fun uidMustBeExactlyZeroAndNeverArbitraryRootOutput() {
        assertTrue(RootCommand.isRootUid("0\n"))
        for (output in listOf("", "1000", "uid=0(root)", "permission denied", "0\nwarning", "00")) {
            assertFalse(output, RootCommand.isRootUid(output))
        }
    }

    @Test fun startupAndManualRequestsSharePromptButLaterRequestsRecheckPermission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val requests = RootPermissionRequests(scope)
            val answer = CompletableDeferred<Boolean>()
            var probes = 0
            val startup = async(start = CoroutineStart.UNDISPATCHED) {
                requests.request(startup = true) { probes++; answer.await() }
            }
            val manual = async(start = CoroutineStart.UNDISPATCHED) {
                requests.request { probes++; true }
            }
            assertEquals(1, probes)
            assertNull(requests.request(startup = true) { error("startup ran twice") })
            answer.complete(false)
            assertEquals(false, startup.await())
            assertEquals(false, manual.await())
            assertEquals(true, requests.request { probes++; true })
            assertEquals(false, requests.request { probes++; false })
            assertEquals(3, probes)
        } finally { scope.cancel() }
    }

    @Test fun leavingActivityDoesNotCancelSharedPromptOrStartAnotherOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val requests = RootPermissionRequests(scope)
            val answer = CompletableDeferred<Boolean>()
            var probes = 0
            val activity = async(start = CoroutineStart.UNDISPATCHED) {
                requests.request(startup = true) { probes++; answer.await() }
            }
            activity.cancel()
            val manual = async(start = CoroutineStart.UNDISPATCHED) {
                requests.request { probes++; false }
            }
            assertEquals(1, probes)
            answer.complete(true)
            assertEquals(true, manual.await())
            assertNull(requests.request(startup = true) { error("startup ran twice") })
        } finally { scope.cancel() }
    }
}
