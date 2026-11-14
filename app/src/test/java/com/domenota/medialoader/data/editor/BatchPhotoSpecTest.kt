package com.domenota.medialoader.data.editor
import org.junit.Assert.*
import org.junit.Test
class BatchPhotoSpecTest {
 @Test fun resizePreservesRatioAndNeverUpscales(){
  assertEquals(1920 to 1080,BatchPhotoSpec().dimensions(3840,2160))
  assertEquals(1080 to 1920,BatchPhotoSpec().dimensions(2160,3840))
  assertEquals(640 to 480,BatchPhotoSpec().dimensions(640,480))
  assertEquals(3840 to 2160,BatchPhotoSpec(maxSide=0).dimensions(3840,2160))
 }
 @Test fun formatMimeMatchesExtension(){
  assertEquals("image/jpeg",BatchPhotoFormat.JPEG.mime)
  assertEquals("webp",BatchPhotoFormat.WEBP.extension)
 }
}
