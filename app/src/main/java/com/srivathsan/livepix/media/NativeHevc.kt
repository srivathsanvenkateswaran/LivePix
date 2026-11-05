package com.srivathsan.livepix.media

/** libde265 via JNI; handles the monochrome (4:0:0) HEVC that Android's decoders reject. */
object NativeHevc {
    init {
        System.loadLibrary("livepix_native")
    }

    @JvmStatic
    external fun decodeLuma(annexB: ByteArray): GrayImage?

    /** ARGB pixels of a colour HEVC still; [sizeOut] receives width and height. */
    @JvmStatic
    external fun decodeArgb(annexB: ByteArray, matrix: Int, fullRange: Boolean, sizeOut: IntArray): IntArray?
}
