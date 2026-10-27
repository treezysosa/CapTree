package studio.retro.ai.media

/** Bounded value snapshots; consecutive keystrokes/slider updates can form one undo step. */
class EditHistory<T>(private val limit:Int=30,private val groupWindowMs:Long=650) {
    private val past=ArrayDeque<T>(); private val future=ArrayDeque<T>()
    private var lastGroup:String?=null; private var lastTime=Long.MIN_VALUE
    init { require(limit>0 && groupWindowMs>=0) }
    val canUndo:Boolean get()=past.isNotEmpty()
    val canRedo:Boolean get()=future.isNotEmpty()
    fun record(before:T,after:T,group:String?=null,timeMs:Long=0) {
        if(before==after)return
        val coalesce=group!=null && group==lastGroup && timeMs>=lastTime && timeMs-lastTime<=groupWindowMs && past.isNotEmpty() && future.isEmpty()
        if(!coalesce) { past.addLast(before); if(past.size>limit)past.removeFirst() }
        future.clear(); lastGroup=group; lastTime=timeMs
    }
    fun undo(current:T):T? {
        if(past.isEmpty())return null
        future.addLast(current); endGroup(); return past.removeLast()
    }
    fun redo(current:T):T? {
        if(future.isEmpty())return null
        past.addLast(current); endGroup(); return future.removeLast()
    }
    fun states():List<T> = past.toList()+future.toList()
    fun clear() { past.clear(); future.clear(); endGroup() }
    fun endGroup() { lastGroup=null; lastTime=Long.MIN_VALUE }
}
