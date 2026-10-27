package studio.retro.ai.media

/** Selects existing frames near a regular cadence, preserving the first frame and stream time. */
class FrameCadence(fps:Int) {
    private val intervalUs=1000000.0/fps.also { require(it in 1..60) }
    private var nextUs:Double?=null
    private var lastUs=Long.MIN_VALUE
    fun keep(timeUs:Long):Boolean {
        val next=nextUs
        if(next==null || timeUs<lastUs) {
            lastUs=timeUs; nextUs=timeUs+intervalUs; return true
        }
        lastUs=timeUs
        if(timeUs+0.5<next)return false
        val skipped=((timeUs-next)/intervalUs).toLong().coerceAtLeast(0)
        nextUs=next+(skipped+1)*intervalUs
        return true
    }
    fun reset() { nextUs=null; lastUs=Long.MIN_VALUE }
}
