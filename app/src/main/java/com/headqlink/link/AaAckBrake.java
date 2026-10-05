package com.headqlink.link;

import com.andrerinas.openheadunit.decoder.video.VideoTap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.qdauto.core.session.FrameCompletion;
import dev.qdauto.core.session.FrameOutcome;

/**
 * Freno a Android Auto en el reenvío directo (qdauto §4.8): el MediaAck de cada frame de AA se retiene hasta que ese
 * frame ha salido hacia el coche y la cola del kernel ha bajado de DRAIN_OUTQ (como mucho MAX_WAIT_MS desde el fin
 * del write). Con la ventana 2 de AA, AA nunca va más de dos frames por delante de lo que el coche recibe.
 *
 * Contrato de VideoTap.AckGate: hold(release) == true obliga a ejecutar release exactamente una vez (AckSlot).
 * lastSlot lo fija el sink en cada unidad (a la ranura nueva o a null) y lo consume gate.hold, los dos en el hilo de
 * vídeo de AA: el ack de un mensaje que no entregó frame nunca se cuelga de la ranura de otro. Sin sesión, el sink no
 * crea ranuras y AA confirma en el acto.
 */
final class AaAckBrake {
    static final int DRAIN_OUTQ = 24 * 1024;
    static final long MAX_WAIT_MS = 250;
    private static final long POLL_MS = 2;

    /** Hilo de vídeo de AA: ranura del último frame entregado (o null). */
    private AckSlot lastSlot;

    final VideoTap.AckGate gate = r -> {
        AckSlot s = lastSlot;
        lastSlot = null;
        return s != null && s.hold(r);
    };
    private final Set<AckSlot> pending = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final LinkedBlockingQueue<Drain> written = new LinkedBlockingQueue<>();
    private final Thread drainThread;
    private volatile boolean stopped;

    // Estadísticas (ventana de 5 s y totales).
    private final AtomicInteger heldWin = new AtomicInteger();
    private volatile long maxHeldWinMs;
    private final AtomicInteger heldTotal = new AtomicInteger();
    private volatile long maxHeldTotalMs;

    private static final class Drain {
        final AckSlot slot;
        final long writeEndNs;

        Drain(AckSlot slot, long writeEndNs) {
            this.slot = slot;
            this.writeEndNs = writeEndNs;
        }
    }

    AaAckBrake() {
        drainThread = new Thread(this::drainLoop, "aa-ack-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    /** Hilo de vídeo de AA: ranura para el frame que se va a enviar por port. */
    AckSlot newSlot(SessionPort port) {
        AckSlot s = new AckSlot(port);
        pending.add(s);
        lastSlot = s;
        if (stopped) release(s);
        return s;
    }

    /** Hilo de vídeo de AA: esta unidad no lleva ranura (SPS/PPS suelto o sin sesión): AA confirma en el acto. */
    void noSlot() {
        lastSlot = null;
    }

    /** Finalización del frame de la ranura: escrito → a drenar; cualquier otro final → ack ya. */
    FrameCompletion completionFor(AckSlot s) {
        return d -> {
            if (d.getOutcome() == FrameOutcome.WRITTEN && !stopped) {
                written.offer(new Drain(s, d.getWriteEndNanos()));
            } else {
                release(s);
            }
        };
    }

    private void drainLoop() {
        LowLatency.boostCurrentThread();
        while (!stopped) {
            Drain d;
            try {
                d = written.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (d == null) continue;
            long deadline = d.writeEndNs + MAX_WAIT_MS * 1_000_000L;
            SessionPort port = d.slot.port instanceof SessionPort ? (SessionPort) d.slot.port : null;
            try {
                while (!stopped && port != null && !port.getClosed() && System.nanoTime() < deadline) {
                    int q = port.drainOutq();
                    if (q < 0 || q <= DRAIN_OUTQ) break;
                    Thread.sleep(POLL_MS);
                }
            } catch (InterruptedException e) {
                release(d.slot);
                break;
            }
            release(d.slot);
        }
        // Al parar, lo que quede se suelta (stop() ya lo hace; esto cubre la carrera).
        List<Drain> left = new ArrayList<>();
        written.drainTo(left);
        for (Drain d : left) release(d.slot);
    }

    private void release(AckSlot s) {
        long held = s.release();
        pending.remove(s);
        if (held >= 0) {
            heldWin.incrementAndGet();
            heldTotal.incrementAndGet();
            if (held > maxHeldWinMs) maxHeldWinMs = held;
            if (held > maxHeldTotalMs) maxHeldTotalMs = held;
            PerfTrace.event("aa_ack", held);
        }
    }

    /** "freno AA: N acks retenidos, máx M ms" de la ventana, y la reinicia. */
    String takeWindowLine() {
        int n = heldWin.getAndSet(0);
        long max = maxHeldWinMs;
        maxHeldWinMs = 0;
        return String.format(java.util.Locale.US, "freno AA: %d acks retenidos, máx %d ms", n, max);
    }

    int heldTotal() {
        return heldTotal.get();
    }

    long maxHeldTotalMs() {
        return maxHeldTotalMs;
    }

    /** Suelta todo lo pendiente (si no, AA dejaría de mandar vídeo) y termina el hilo. */
    void stop() {
        stopped = true;
        drainThread.interrupt();
        List<AckSlot> all = new ArrayList<>(pending);
        for (AckSlot s : all) release(s);
        List<Drain> left = new ArrayList<>();
        written.drainTo(left);
        for (Drain d : left) release(d.slot);
    }
}
