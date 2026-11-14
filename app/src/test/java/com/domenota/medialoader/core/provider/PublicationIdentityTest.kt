package com.domenota.medialoader.core.provider
import org.junit.Assert.*
import org.junit.Test
class PublicationIdentityTest {
 @Test fun youtubeAliases(){assertTrue(PublicationIdentity.same("https://youtu.be/abcdefghijk?t=1","https://www.youtube.com/watch?v=abcdefghijk"))}
 @Test fun rutubeAliases(){assertTrue(PublicationIdentity.same("https://rutube.ru/video/abc/?x=1","https://rutube.ru/video/abc"))}
 @Test fun vkAliases(){assertTrue(PublicationIdentity.same("https://vk.com/video-12_34?x=1","https://vkvideo.ru/video-12_34"))}
 @Test fun distinct(){assertFalse(PublicationIdentity.same("https://vk.com/video-12_34","https://vk.com/video-12_35"))}
}
