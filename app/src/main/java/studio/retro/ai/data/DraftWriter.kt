package studio.retro.ai.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Application-owned writes survive activity recreation. Revisions and tombstones reject late saves. */
class DraftWriter(private val dao:SessionDao) {
    private val gate=Mutex()
    private val deleted=HashSet<String>()
    private val clock=AtomicLong(0)
    private val pending=ConcurrentHashMap<String,Set<String>>()
    fun retain(id:String,revision:Long,media:Set<String>):String="$id:$revision".also { pending[it]=media }
    fun release(key:String) { pending.remove(key) }
    fun revision(after:Long=0)=clock.updateAndGet { maxOf(it+1,after+1,System.currentTimeMillis()*1000) }
    suspend fun save(value:EditorSession)=gate.withLock {
        if(value.id in deleted)false else dao.putIfNewer(value)
    }
    suspend fun delete(id:String)=gate.withLock { deleted+=id; dao.delete(id) }
    suspend fun <T> withRetainedMedia(block:suspend(Set<String>)->T):T=gate.withLock {
        block(dao.media().flatMap { EditorCheckpoints.mediaPaths(it) }.toSet()+pending.values.flatten())
    }
}
