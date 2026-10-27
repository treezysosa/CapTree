package studio.retro.ai.media

data class AnimationOptions(val fps:Int=8,val speed:Float=1f,val longestEdge:Int=320,val caption:String="",val sticker:String="") {
    init { require(fps in 1..24 && speed in 0.25f..4f && longestEdge in 64..1920) }
}
