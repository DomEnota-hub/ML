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

object PublicationIdentity {
    fun key(raw:String):String? {
        UsedInstagramLink.key(raw)?.let{return "instagram:"+it}
        val u=runCatching{java.net.URI(raw.trim())}.getOrNull()?:return null
        val host=u.host?.lowercase()?.removePrefix("www.")?:return null
        val parts=u.path.orEmpty().split('/').filter{it.isNotEmpty()}
        val query=u.rawQuery.orEmpty().split('&').mapNotNull{val p=it.split('=',limit=2);if(p.size==2)p[0] to p[1] else null}.toMap()
        if(host in setOf("youtube.com","m.youtube.com","youtu.be")){
            val id=when{host=="youtu.be"->parts.firstOrNull();parts.firstOrNull()=="watch"->query["v"];parts.firstOrNull() in setOf("shorts","live","embed")->parts.getOrNull(1);else->null}
            if(id!=null&&id.matches(Regex("[A-Za-z0-9_-]{11}")))return "youtube:"+id
        }
        if(host=="rutube.ru"&&parts.firstOrNull()=="video"&&parts.size==2)return "rutube:"+parts[1]
        if(host in setOf("vk.com","m.vk.com","vkvideo.ru")){
            val id=Regex("(?:video|clip|wall)-?\\d+_\\d+").find(u.path.orEmpty())?.value
            if(id!=null)return "vk:"+id
        }
        return null
    }
    fun same(a:String,b:String):Boolean=key(a)?.let{it==key(b)}?: (a.trim()==b.trim())
}
