package com.c10link.link;

import android.view.Surface;

/** Modo debug: patrón de prueba dibujado con Canvas. */
final class PatternSource implements VideoSource {
    private final String banner;
    private TestPattern pattern;

    PatternSource(String banner) {
        this.banner = banner;
    }

    @Override
    public void start(Surface surface, int width, int height, int fps, String info) {
        pattern = new TestPattern(surface, width, height, fps, banner != null ? banner : info);
        pattern.start();
    }

    @Override
    public void touch(int action, float x, float y) {
        if (pattern != null) pattern.addTouch(action, x, y);
    }

    @Override
    public void setStatus(String status) {
        if (pattern != null) pattern.setStatus(status);
    }

    @Override
    public String takeJitterSummary() {
        return pattern != null ? pattern.renderJitter.takeSummary() : null;
    }

    @Override
    public void stop() {
        if (pattern != null) pattern.shutdown();
    }
}
