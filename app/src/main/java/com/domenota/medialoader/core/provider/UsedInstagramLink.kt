package com.domenota.medialoader.core.provider

/** Publication identity, not a CDN URL or a tracking parameter. */
object UsedInstagramLink {
    fun key(raw:String):String?=InstagramLinkParser.parse(raw)?.let{
        (if(it.isStory)"story:" else "post:")+it.id
    }
    fun same(a:String,b:String):Boolean {
        val key=key(a)
        return if(key!=null)key==key(b) else a.trim()==b.trim()
    }
}
