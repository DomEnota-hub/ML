package com.domenota.medialoader.core.provider
import org.junit.Assert.*
import org.junit.Test
class UsedInstagramLinkTest {
 @Test fun trackingAndPathVariantsShareIdentity(){
  assertTrue(UsedInstagramLink.same("https://www.instagram.com/p/ABCdef123/?igsh=xyz","https://instagram.com/reel/ABCdef123"))
  assertFalse(UsedInstagramLink.same("https://instagram.com/p/ABCdef123","https://instagram.com/p/abcdef123"))
  assertFalse(UsedInstagramLink.same("https://instagram.com/stories/user/123456","https://instagram.com/p/123456"))
  assertNull(UsedInstagramLink.key("https://example.com/p/ABCdef123"))
 }
}
