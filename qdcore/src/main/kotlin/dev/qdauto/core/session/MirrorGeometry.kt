package dev.qdauto.core.session

/**
 * Tamaños que QDLink calcula a partir de `CAR_INFO` y de la pantalla del teléfono, con la misma aritmética
 * `float` (spec §8.6; QDLink: SC/service/ScreenCaptureService.java:466-661, dex `H @0119-0193`).
 * Solo screenType 0 (el caso del C10; los `CarType` 28B/297/298/299/29A/29B/2C7 usan otra rama que no se replica).
 *
 * - [inCarWidth]×[inCarHeight]: tamaño del encoder/SPS y W×H de la cabecera en modo in-app (par(CarW)×par(CarH)).
 * - [mirrorWidth]×[mirrorHeight]: outHor = `PHONE_INFO.MirrorWidth/MirrorHeight` y W×H de la cabecera en espejo.
 */
data class MirrorGeometry(
    val inCarWidth: Int,
    val inCarHeight: Int,
    val mirrorWidth: Int,
    val mirrorHeight: Int,
    val outVerWidth: Int,
    val outVerHeight: Int,
    val tempLong: Int,
    val tempShort: Int,
) {
    companion object {
        /** QDLink sustituye 0×0 por 800×480 (LC/a.java:919). */
        fun forCarInfo(phoneLongSide: Int, phoneShortSide: Int, carWidth: Int, carHeight: Int): MirrorGeometry =
            if (carWidth == 0 && carHeight == 0) {
                compute(phoneLongSide, phoneShortSide, 800, 480)
            } else {
                compute(phoneLongSide, phoneShortSide, carWidth, carHeight)
            }

        fun compute(phoneLongSide: Int, phoneShortSide: Int, carWidth: Int, carHeight: Int): MirrorGeometry {
            val pL = maxOf(phoneLongSide, phoneShortSide)
            val pS = minOf(phoneLongSide, phoneShortSide)
            return if (carWidth > carHeight) landscape(pL, pS, carWidth, carHeight) else portrait(pL, pS, carWidth, carHeight)
        }

        /** Coche horizontal (`carW > carH`), ScreenCaptureService.java:530-565. */
        private fun landscape(pL: Int, pS: Int, carW: Int, carH: Int): MirrorGeometry {
            val inW = even(carW)
            val inH = even(carH)
            val rc = carW.toFloat() / carH.toFloat() // valores crudos, división float (dex @0119-011b)
            val rp = pL.toFloat() / pS.toFloat()
            var outW: Int
            var outH: Int
            if (rc > rp) {
                outW = (inH.toFloat() * rp).toInt()
                outH = inH
            } else {
                outW = inW
                outH = (inW.toFloat() / rp).toInt()
            }
            outW = even(outW)
            outH = even(outH)
            val outVerW = even((inH.toFloat() / rp).toInt())
            // tempLong = max(pL, E.f()) con E.f() aún a 0 (:542, :548).
            val tempLong = pL
            val tempShort = (tempLong.toFloat() / rc).toInt()
            return MirrorGeometry(inW, inH, outW, outH, outVerW, inH, tempLong, tempShort)
        }

        /** Coche vertical o cuadrado con screenType 0, ScreenCaptureService.java:566-606: se trata como horizontal. */
        private fun portrait(pL: Int, pS: Int, carW: Int, carH: Int): MirrorGeometry {
            val inW = even(maxOf(carW, carH))
            val inH = even(minOf(carW, carH))
            val f9 = inW.toFloat() / inH.toFloat()
            val f10 = pL.toFloat() / pS.toFloat() // jadx muestra `i15 / i17`; el bytecode es float (spec Anexo A)
            val outW: Int
            val outH: Int
            if (f9 > f10) {
                outW = (inH.toFloat() * f10).toInt()
                outH = inH
            } else {
                outW = inW
                outH = (inW.toFloat() / f10).toInt()
            }
            // En esta rama QDLink no redondea outHor a par y deja outVer a 0×0. [INFERENCIA] temp* en float.
            val tempLong: Int
            val tempShort: Int
            if (f9 > f10) {
                tempShort = pS
                tempLong = (pS.toFloat() * f9).toInt()
            } else {
                tempLong = pL
                tempShort = (pL.toFloat() / f9).toInt()
            }
            return MirrorGeometry(inW, inH, outW, outH, 0, 0, tempLong, tempShort)
        }

        /** "Redondeo a par" de QDLink: +1 si es impar. */
        fun even(v: Int): Int = if (v % 2 != 0) v + 1 else v
    }
}
