package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Sesiones» de Diagnóstico: el CSV (con cabeceras repetidas) en filas resumidas, la más reciente primero. */
class SessionsViewTest {
    private val header = "inicio,fin,duracion_s,sesion,motor,conexion,modo_video,perfil,coche_ip,coche_nombre,iface,cierre," +
        "cierre_detalle,llego_a_video,t_car_info_ms,t_video_ctrl_ms,t_primer_frame_ms,t_primer_idr_ms,frames,fps,kbps,idr," +
        "keyframe_req_coche,descartados,vaciados,max_write_ms,max_cola_ms,heartbeats_coche,toques,max_hueco_coche_ms,cortes," +
        "max_corte_ms,retrans,reconexion_ms,video_reutilizado,ciclos_foco_aa,termico"

    @Test
    fun rowsFollowTheLastHeaderAndComeNewestFirst() {
        val lines = listOf(
            header,
            "2026-10-09 22:47:18.323,2026-10-09 22:47:28.326,10.0,7,qdauto,usb,aa_ext,coche,usb,,usb (cable USB),WATCHDOG," +
                "10000 ms sin recibir nada del coche,0,-1,-1,-1,-1,0,0.0,0,0,0,0,0,0,0,0,0,0,0,0,-1,-1,0,0,0",
            header,
            "2026-10-09 22:47:41.113,2026-10-09 22:55:12.892,451.8,8,qdauto,usb,aa_ext,coche,usb,\"Leap, C10\",usb (cable USB),LOCAL," +
                "servicio parado,1,11,41,110,110,15939,35.3,1897,11,2,0,0,32,15,148,199,3043,2,3043,-1,-1,0,0,0,extra",
        )
        val rows = SessionsView.parse(lines, 30)
        assertEquals(2, rows.size)
        val r = rows[0]
        assertEquals("2026-10-09 22:47:41.113", r.start)
        assertEquals(451.8, r.seconds, 0.01)
        assertEquals("usb", r.link)
        assertEquals(35.3, r.fps, 0.01)
        assertEquals(2, r.cuts)
        assertEquals(3043, r.maxCutMs)
        assertTrue(r.video)
        assertEquals("servicio parado", r.end)
        assertFalse(rows[1].video)
        assertEquals(1, SessionsView.parse(lines, 1).size)
    }

    @Test
    fun quotedCellsAndDurations() {
        assertEquals(listOf("a", "b, c", "d\"e", ""), SessionsView.split("a,\"b, c\",\"d\"\"e\","))
        assertEquals("45 s", SessionsView.duration(45.0))
        assertEquals("7 min 32 s", SessionsView.duration(451.8))
        assertEquals("1 h 5 min", SessionsView.duration(3900.0))
    }
}
