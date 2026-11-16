package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative source-pixel mask for OCR symbol candidates.
 *
 * Rectangles are search limits, NEVER masks. This only makes a mask when a
 * locally verified background can be distinguished from visible ink. Text on
 * a textured/ambiguous background is deliberately left for manual correction
 * or a verified segmentation model; it must not erase scenery by guessing.
 */
internal object GlyphContentMask {
    private const val MAX_SYMBOLS = 160
    private const val MAX_PIXELS_PER_SYMBOL = 600_000
    private const val MAX_STROKES = 8_000

    internal data class Analysis(val confirmed: List<MaskStroke>, val unresolved: List<DetectedGlyph>,
        val reasons: Map<String,Int>) {
        val complete: Boolean get() = unresolved.isEmpty() && confirmed.isNotEmpty()
        val strokes: List<MaskStroke> get() = if(complete)confirmed else emptyList()
    }
    fun fromBitmap(source: Bitmap, glyphs: Collection<DetectedGlyph>): List<MaskStroke> = analyze(source,glyphs).strokes

    /** All selected glyphs must pass before automatic inpainting. Proven partial
     * masks may be used only as an explicitly reviewed manual starting point. */
    fun analyze(source: Bitmap, glyphs: Collection<DetectedGlyph>): Analysis {
        if (glyphs.isEmpty() || glyphs.size > MAX_SYMBOLS || source.width < 2 || source.height < 2)
            return Analysis(emptyList(),glyphs.toList(),mapOf("selection_limit_or_empty" to glyphs.size))
        val result = ArrayList<MaskStroke>()
        val unresolved = ArrayList<DetectedGlyph>()
        val reasons = mutableMapOf<String,Int>()
        val backgrounds = mutableMapOf<Int, Background>()
        val profiles = mutableMapOf<Int, Background>()
        val outlined = mutableMapOf<Int, List<MaskStroke>>()
        var outlinedStrokeCount = 0
        val outlinedLineGlyphs = mutableSetOf<Int>()
        for (line in OcrLineCandidatePolicy.credibleLines(glyphs.toList())) {
            if (line.count { it.confidence >= 75f && it.text.any(Char::isLetterOrDigit) } < 4) continue
            lineBackground(source,line)?.let { bg -> line.forEach { backgrounds[it.id]=bg } }
            // On a gradient or a vertical seam, the exterior above and below
            // each column can agree even when the whole line has no one color.
            columnBackground(source,line)?.let { bg -> line.forEach { profiles[it.id]=bg } }
            // Repeated style evidence is mandatory. Resolve shared outline
            // components over the whole selected line, not clipped glyph crops.
            val eligible = line.filter { it.confidence >= 75f && it.text.any(Char::isLetterOrDigit) }
            val style = extractOutlinedLine(source, eligible)
            if (style.size >= 3 && (style.size * 2 >= eligible.size || style.size >= 5)) {
                val count = style.values.sumOf { it.size }
                if (outlinedStrokeCount + count <= MAX_STROKES) {
                    outlined.putAll(style)
                    outlinedLineGlyphs.addAll(eligible.map { it.id })
                    outlinedStrokeCount += count
                }
            }
        }
        for (glyph in glyphs) {
            var reason="no_verified_ink"
            fun report(value:String){reason=value}
            val profile=profiles[glyph.id]
            // Direct repeated fill/outline evidence is stronger than a
            // guessed single background colour for that glyph. Preserve the
            // prior verified-background method for all other text styles.
            val segment = outlined[glyph.id] ?: if (glyph.id in outlinedLineGlyphs) {
                report("outlined_component_unverified"); null
            } else if(profile!=null) extractSymbol(source,glyph,profile,::report)
                else extractSymbol(source,glyph,null,::report) ?: backgrounds[glyph.id]?.let {
                    extractSymbol(source,glyph,it,::report)
                }
            if (segment.isNullOrEmpty()) {
                unresolved.add(glyph); reasons[reason]=(reasons[reason]?:0)+1
                continue
            }
            if(result.size+segment.size>MAX_STROKES) {
                return Analysis(emptyList(),glyphs.toList(),mapOf("stroke_limit" to glyphs.size))
            }
            result.addAll(segment)
        }
        return Analysis(result,unresolved,reasons)
    }

    private fun extractOutlinedLine(source: Bitmap, glyphs: List<DetectedGlyph>): Map<Int,List<MaskStroke>> {
        if (glyphs.size < 3) return emptyMap()
        val w=source.width;val h=source.height
        val absolute=glyphs.map { glyph ->
            val l=(glyph.left*w).toInt();val t=(glyph.top*h).toInt()
            val r=kotlin.math.ceil(glyph.right*w).toInt().coerceAtMost(w)
            val b=kotlin.math.ceil(glyph.bottom*h).toInt().coerceAtMost(h)
            val pad=(min(r-l,b-t)*.22f).toInt().coerceIn(2,8)
            OutlinedGlyphInk.Region(l,t,r,b,pad)
        }
        // Two extra exterior pixels distinguish cropped strokes from a real
        // background edge. Per-glyph padding still limits selectable ink.
        val x0=(absolute.minOf{it.left-it.pad}-2).coerceAtLeast(0)
        val y0=(absolute.minOf{it.top-it.pad}-2).coerceAtLeast(0)
        val rw=(absolute.maxOf{it.right+it.pad}+2).coerceAtMost(w)-x0
        val rh=(absolute.maxOf{it.bottom+it.pad}+2).coerceAtMost(h)-y0
        if(rw<7 || rh<7 || rw.toLong()*rh>MAX_PIXELS_PER_SYMBOL)return emptyMap()
        val regions=absolute.map { it.copy(left=it.left-x0,top=it.top-y0,right=it.right-x0,bottom=it.bottom-y0) }
        val pixels=IntArray(rw*rh);source.getPixels(pixels,0,rw,x0,y0,rw,rh)
        val result=OutlinedGlyphInk.extractRegions(pixels,rw,rh,regions)?:return emptyMap()
        val output=mutableMapOf<Int,MutableList<MaskStroke>>();var count=0
        val diameter=(2f/min(w,h)).coerceIn(.00001f,.5f)
        for(y in 0 until rh){var x=0;while(x<rw){
            val id=result.owner[y*rw+x]
            if(id<0){x++;continue}
            val start=x;while(x<rw && result.owner[y*rw+x]==id)x++
            val end=x-1;val yy=(y0+y+.5f)/h
            val first=MaskPoint((x0+start+.5f)/w,yy);val last=MaskPoint((x0+end+.5f)/w,yy)
            if(++count>MAX_STROKES)return emptyMap()
            output.getOrPut(glyphs[id].id){mutableListOf()}.add(MaskStroke(if(start==end)listOf(first)else listOf(first,last),diameter))
        }}
        return output
    }

    private data class Background(val color: Int, val q75: Int, val columns: IntArray?=null, val left: Int=0) {
        fun at(x:Int):Int = columns?.let{it[(x-left).coerceIn(0,it.lastIndex)]} ?: color
    }

    /** Strong exterior evidence for a local smooth gradient or a single seam.
     * Both independent bands must agree per column; texture, opposing bands,
     * clipping and multiple sharp transitions deliberately fail closed. */
    private fun columnBackground(source:Bitmap,line:List<DetectedGlyph>):Background? {
        val l=(line.minOf{it.left}*source.width).toInt();val r=kotlin.math.ceil(line.maxOf{it.right}*source.width).toInt()
        val t=(line.minOf{it.top}*source.height).toInt();val b=kotlin.math.ceil(line.maxOf{it.bottom}*source.height).toInt()
        val pad=((b-t)*.30f).toInt().coerceIn(3,16)
        val x0=(l-pad).coerceAtLeast(0);val x1=(r+pad).coerceAtMost(source.width)
        if(t-pad<0 || b+pad>source.height || x1-x0<12 || (x1-x0).toLong()*pad*2>MAX_PIXELS_PER_SYMBOL)return null
        val w=x1-x0;val upper=IntArray(w*pad);val lower=IntArray(w*pad)
        source.getPixels(upper,0,w,x0,t-pad,w,pad)
        source.getPixels(lower,0,w,x0,b,w,pad)
        val colors=IntArray(w);var sharp=0;var rough=0;var maxNoise=0
        for(x in 0 until w){
            val top=List(pad){upper[it*w+x]};val bottom=List(pad){lower[it*w+x]}
            if((top+bottom).any{((it ushr 24) and 255)<200})return null
            val a=medianColor(top);val z=medianColor(bottom)
            if(distance(a,z)>12)return null
            val median=medianColor(top+bottom)
            val deviations=(top+bottom).map{distance(it,median)}.sorted()
            val q=deviations[(deviations.size-1)*3/4]
            if(q>12 || deviations.last()>24)return null
            maxNoise=max(maxNoise,q);colors[x]=median
            if(x>0){val delta=distance(colors[x-1],median);if(delta>26)sharp++;if(delta>8)rough++}
        }
        if(sharp>1 || rough>max(2,w/12))return null
        // Uniform backgrounds already use the existing, separately verified path.
        if(colors.all{distance(it,colors.first())<=18})return null
        return Background(colors.first(),maxNoise,colors,x0)
    }

    private fun verifiedBackground(boundary: List<Int>): Background? {
        if (boundary.size < 12) return null
        val color = medianColor(boundary)
        val deviations = boundary.map { distance(it, color) }.sorted()
        if (deviations[deviations.size / 2] > 18 ||
            deviations.count { it <= 30 } * 100L < deviations.size * 72L) return null
        return Background(color, deviations[(deviations.size - 1) * 3 / 4])
    }

    private fun lineBackground(source: Bitmap, line: List<DetectedGlyph>): Background? {
        val width = source.width; val height = source.height
        val l = (line.minOf { it.left } * width).toInt()
        val r = kotlin.math.ceil(line.maxOf { it.right } * width).toInt()
        val t = (line.minOf { it.top } * height).toInt()
        val b = kotlin.math.ceil(line.maxOf { it.bottom } * height).toInt()
        val pad = ((b - t) * .30f).toInt().coerceIn(3, 24)
        val x0 = (l - pad).coerceAtLeast(0); val x1 = (r + pad).coerceAtMost(width)
        val y0 = (t - pad).coerceAtLeast(0); val y1 = (b + pad).coerceAtMost(height)
        if (x1 - x0 < 5 || y1 - y0 < 5 || (x1 - x0).toLong() * (y1 - y0) > MAX_PIXELS_PER_SYMBOL) return null
        // Full perimeter, plus the genuine exterior band, not a decimated grid.
        val pixels = IntArray((x1 - x0) * (y1 - y0))
        source.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        val samples = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) {
            if (x < l || x >= r || y < t || y >= b) samples.add(pixels[(y-y0)*(x1-x0)+x-x0])
        }
        return verifiedBackground(samples)?.takeIf { it.q75 <= 18 }
    }

    private fun extractSymbol(source: Bitmap, glyph: DetectedGlyph, lineBackground: Background? = null,
        report: (String)->Unit = {}): List<MaskStroke>? {
        fun reject(reason:String):List<MaskStroke>?{report(reason);return null}
        val width = source.width
        val height = source.height
        val left = (glyph.left * width).toInt().coerceIn(0, width - 1)
        val top = (glyph.top * height).toInt().coerceIn(0, height - 1)
        val right = kotlin.math.ceil(glyph.right * width).toInt().coerceIn(left + 1, width)
        val bottom = kotlin.math.ceil(glyph.bottom * height).toInt().coerceIn(top + 1, height)
        val pad = (min(right - left, bottom - top) * .18f).toInt().coerceIn(2, 16)
        val x0 = max(0, left - pad)
        val y0 = max(0, top - pad)
        val x1 = min(width, right + pad)
        val y1 = min(height, bottom + pad)
        val regionWidth = x1 - x0
        val regionHeight = y1 - y0
        if (regionWidth < 5 || regionHeight < 5 ||
            regionWidth.toLong() * regionHeight > MAX_PIXELS_PER_SYMBOL) return reject("symbol_region_limit")

        val pixels = IntArray(regionWidth * regionHeight)
        source.getPixels(pixels, 0, regionWidth, x0, y0, regionWidth, regionHeight)
        // Sample the entire perimeter, not a regularly decimated grid.
        // Even strides can sample only one colour of a periodic texture
        // (e.g. a checkerboard), making a noisy background appear uniform.
        val boundary = ArrayList<Int>(2 * (regionWidth + regionHeight) - 4)
        for (x in 0 until regionWidth) {
            boundary.add(pixels[x])
            boundary.add(pixels[(regionHeight - 1) * regionWidth + x])
        }
        for (y in 1 until regionHeight - 1) {
            boundary.add(pixels[y * regionWidth])
            boundary.add(pixels[y * regionWidth + regionWidth - 1])
        }
        val estimate = lineBackground ?: verifiedBackground(boundary) ?: return reject("background_unverified")
        val q75 = estimate.q75
        if (lineBackground != null) {
            // The alternative is permitted only where the character itself
            // still contains real background evidence. Never fill a box.
            if (glyph.confidence < 75f || pixels.indices.count { index ->
                distance(pixels[index],estimate.at(x0+index%regionWidth))<=18
            } * 100L < pixels.size * 20L) return reject("insufficient_background_evidence")
        }

        // A thin grey outline can be much less than 43 RGB levels from
        // the background. Lower the threshold only for a coherent OCR
        // candidate with locally consistent background, never globally.
        val threshold = if (glyph.confidence >= 65f && q75 <= 18)
            (q75 * 2 + 6).coerceIn(14, 43) else 43
        val foreground = BooleanArray(pixels.size)
        val margin = min(2, pad)
        for (index in pixels.indices) {
            val x = x0 + index % regionWidth
            val y = y0 + index / regionWidth
            val color = pixels[index]
            foreground[index] = x in (left - margin) until (right + margin) &&
                y in (top - margin) until (bottom + margin) &&
                ((color ushr 24) and 255) >= 200 && distance(color, estimate.at(x)) >= threshold
        }
        // Remove isolated single-pixel noise without filling glyph holes or
        // discarding real punctuation/diacritics with neighbouring pixels.
        val coherent = foreground.copyOf()
        for (index in foreground.indices) if (foreground[index]) {
            val x = index % regionWidth; val y = index / regionWidth
            var neighbour = false
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until regionWidth && ny in 0 until regionHeight &&
                    foreground[ny * regionWidth + nx]) neighbour = true
            }
            if (!neighbour) coherent[index] = false
        }
        coherent.copyInto(foreground)
        val ink = foreground.count { it }
        // Blank or largely selected rectangles are unsafe. Require that the
        // visible ink has a plausible occupancy within a character region.
        if (ink < 3 || ink * 100L < pixels.size.toLong() ||
            ink * 100L > pixels.size.toLong() * (if (lineBackground == null || lineBackground.columns!=null) 62 else 76)) return reject("ink_occupancy")

        return rasterStrokes(foreground,regionWidth,regionHeight,x0,y0,width,height)
            ?: reject("stroke_limit")
    }

    private fun rasterStrokes(foreground: BooleanArray, regionWidth: Int, regionHeight: Int,
        x0: Int, y0: Int, width: Int, height: Int): List<MaskStroke>? {
        val strokes = ArrayList<MaskStroke>()
        val short = min(width, height).toFloat()
        val diameter = (2f / short).coerceIn(.00001f, .5f)
        for (y in 0 until regionHeight) {
            var x = 0
            while (x < regionWidth) {
                if (!foreground[y * regionWidth + x]) { x++; continue }
                val start = x
                while (x < regionWidth && foreground[y * regionWidth + x]) x++
                val end = x - 1
                val yy = (y0 + y + .5f) / height
                val first = MaskPoint((x0 + start + .5f) / width, yy)
                val last = MaskPoint((x0 + end + .5f) / width, yy)
                strokes.add(MaskStroke(if (start == end) listOf(first) else listOf(first, last), diameter))
                if (strokes.size > MAX_STROKES) return null
            }
        }
        return strokes
    }

    private fun medianColor(colors: List<Int>): Int {
        fun channel(shift: Int): Int =
            colors.map { (it ushr shift) and 255 }.sorted()[colors.size / 2]
        return (255 shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    private fun distance(a: Int, b: Int): Int =
        (abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
            abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) +
            abs((a and 255) - (b and 255))) / 3
}
