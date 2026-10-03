package com.c10link.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Juegos sencillos para las esperas con el coche parado: 2048, memoria y tres en raya. */
final class GamesScreen implements CarScreen {
    private Host host;
    private View menu;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[][] games = {{"2048", "Desliza para unir números"}, {"Memoria", "Encuentra las parejas"}, {"Tres en raya", "Contra el móvil"}};
        for (int i = 0; i < games.length; i++) {
            int idx = i;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackground(CarStyle.round(CarStyle.ITEM_BG, 24));
            card.addView(CarStyle.text(c, games[i][0], 44, CarStyle.TEXT));
            TextView sub = CarStyle.text(c, games[i][1], 22, CarStyle.TEXT_DIM);
            sub.setPadding(0, 12, 0, 0);
            card.addView(sub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(420, 300);
            lp.setMargins(20, 0, 20, 0);
            row.addView(card, lp);
            card.setOnClickListener(v -> open(idx));
        }
        menu = row;
        return row;
    }

    private void open(int game) {
        Context c = host.context();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(CarStyle.BG);
        View v = game == 0 ? new Game2048(c) : game == 1 ? new Memory(c) : new TicTacToe(c);
        f.addView(v, CarStyle.match());
        f.addView(CarStyle.back(c, () -> host.setContent(menu)));
        host.setContent(f);
    }

    /** Base: tablero cuadrado centrado y un texto de estado arriba a la derecha. */
    private abstract static class Board extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String status = "";
        RectF board = new RectF();

        Board(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float side = Math.min(w, h) - 60;
            board.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
        }

        void drawStatus(Canvas cv) {
            p.setColor(CarStyle.TEXT);
            p.setTextSize(30);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(status, getWidth() - 30, 60, p);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void cell(Canvas cv, RectF r, int fill, String label, int textColor, float textSize) {
            p.setColor(fill);
            cv.drawRoundRect(r, 14, 14, p);
            if (label != null) {
                p.setColor(textColor);
                p.setTextSize(textSize);
                cv.drawText(label, r.centerX(), r.centerY() + textSize / 3, p);
            }
        }

        RectF cellRect(int n, int row, int col, float gap) {
            float s = board.width() / n;
            return new RectF(board.left + col * s + gap, board.top + row * s + gap,
                    board.left + (col + 1) * s - gap, board.top + (row + 1) * s - gap);
        }
    }

    // ------------------------------------------------------------------ 2048

    private static final class Game2048 extends Board {
        private final int[][] g = new int[4][4];
        private final Random rnd = new Random();
        private int score;
        private float downX;
        private float downY;

        Game2048(Context c) {
            super(c);
            reset();
        }

        private void reset() {
            for (int[] r : g) java.util.Arrays.fill(r, 0);
            score = 0;
            spawn();
            spawn();
            status = "Puntos 0";
        }

        private void spawn() {
            List<int[]> free = new ArrayList<>();
            for (int r = 0; r < 4; r++) for (int col = 0; col < 4; col++) if (g[r][col] == 0) free.add(new int[]{r, col});
            if (free.isEmpty()) return;
            int[] f = free.get(rnd.nextInt(free.size()));
            g[f[0]][f[1]] = rnd.nextInt(10) == 0 ? 4 : 2;
        }

        /** dir: 0 izquierda, 1 derecha, 2 arriba, 3 abajo. */
        private boolean move(int dir) {
            boolean moved = false;
            for (int i = 0; i < 4; i++) {
                int[] line = new int[4];
                for (int j = 0; j < 4; j++) line[j] = get(dir, i, j);
                int[] out = new int[4];
                int k = 0;
                int last = 0;
                for (int v : line) {
                    if (v == 0) continue;
                    if (last == v) {
                        out[k - 1] = v * 2;
                        score += v * 2;
                        last = 0;
                    } else {
                        out[k++] = v;
                        last = v;
                    }
                }
                for (int j = 0; j < 4; j++) {
                    if (get(dir, i, j) != out[j]) moved = true;
                    set(dir, i, j, out[j]);
                }
            }
            return moved;
        }

        private int get(int dir, int i, int j) {
            switch (dir) {
                case 0: return g[i][j];
                case 1: return g[i][3 - j];
                case 2: return g[j][i];
                default: return g[3 - j][i];
            }
        }

        private void set(int dir, int i, int j, int v) {
            switch (dir) {
                case 0: g[i][j] = v; break;
                case 1: g[i][3 - j] = v; break;
                case 2: g[j][i] = v; break;
                default: g[3 - j][i] = v;
            }
        }

        private boolean canMove() {
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                if (g[r][c] == 0) return true;
                if (c < 3 && g[r][c] == g[r][c + 1]) return true;
                if (r < 3 && g[r][c] == g[r + 1][c]) return true;
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            } else if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!canMove()) {
                    reset();
                } else if (Math.max(Math.abs(dx), Math.abs(dy)) > 60) {
                    int dir = Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? 0 : 1) : (dy < 0 ? 2 : 3);
                    if (move(dir)) spawn();
                    status = canMove() ? "Puntos " + score : "Fin · " + score + " puntos · toca para otra";
                }
                invalidate();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            cell(cv, board, 0xFF2E3238, null, 0, 0);
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                int v = g[r][c];
                int lvl = v == 0 ? 0 : Integer.numberOfTrailingZeros(v);
                int fill = v == 0 ? 0xFF3A3F46 : android.graphics.Color.HSVToColor(new float[]{(40 - lvl * 9 + 360) % 360, 0.55f, 0.45f + Math.min(lvl, 11) * 0.04f});
                cell(cv, cellRect(4, r, c, 8), fill, v == 0 ? null : String.valueOf(v), CarStyle.TEXT, v < 1000 ? 64 : 48);
            }
            drawStatus(cv);
        }
    }

    // ------------------------------------------------------------------ memoria

    private static final class Memory extends Board {
        private static final String[] SYMBOLS = {"★", "♥", "♦", "♣", "♠", "☀", "☂", "♫", "✈", "⚑"};
        private static final int COLS = 5;
        private static final int ROWS = 4;
        private final List<String> cards = new ArrayList<>();
        private final boolean[] open = new boolean[ROWS * COLS];
        private final boolean[] done = new boolean[ROWS * COLS];
        private int first = -1;
        private int second = -1;
        private int moves;

        Memory(Context c) {
            super(c);
            reset();
        }

        private void reset() {
            cards.clear();
            for (String s : SYMBOLS) {
                cards.add(s);
                cards.add(s);
            }
            Collections.shuffle(cards);
            java.util.Arrays.fill(open, false);
            java.util.Arrays.fill(done, false);
            first = second = -1;
            moves = 0;
            status = "Movimientos 0";
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float cw = Math.min((w - 60) / (float) COLS, (h - 60) / (float) ROWS);
            board.set((w - cw * COLS) / 2f, (h - cw * ROWS) / 2f, (w + cw * COLS) / 2f, (h + cw * ROWS) / 2f);
        }

        private RectF rect(int i) {
            float s = board.width() / COLS;
            int r = i / COLS;
            int c = i % COLS;
            return new RectF(board.left + c * s + 8, board.top + r * s + 8, board.left + (c + 1) * s - 8, board.top + (r + 1) * s - 8);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            boolean all = true;
            for (boolean d : done) all &= d;
            if (all) {
                reset();
                invalidate();
                return true;
            }
            if (second >= 0) return true;
            for (int i = 0; i < cards.size(); i++) {
                if (!rect(i).contains(e.getX(), e.getY()) || open[i] || done[i]) continue;
                open[i] = true;
                if (first < 0) {
                    first = i;
                } else {
                    second = i;
                    moves++;
                    if (cards.get(first).equals(cards.get(second))) {
                        done[first] = done[second] = true;
                        first = second = -1;
                    } else {
                        postDelayed(() -> {
                            open[first] = open[second] = false;
                            first = second = -1;
                            invalidate();
                        }, 800);
                    }
                    boolean fin = true;
                    for (boolean d : done) fin &= d;
                    status = fin ? "¡Completado en " + moves + " movimientos! Toca para otra" : "Movimientos " + moves;
                }
                invalidate();
                break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            for (int i = 0; i < cards.size(); i++) {
                boolean shown = open[i] || done[i];
                cell(cv, rect(i), done[i] ? 0xFF2B4A3A : shown ? 0xFF3A4A66 : 0xFF3A3F46,
                        shown ? cards.get(i) : null, CarStyle.TEXT, 72);
            }
            drawStatus(cv);
        }
    }

    // ------------------------------------------------------------------ tres en raya

    private static final class TicTacToe extends Board {
        private final int[] b = new int[9];
        private int result;

        TicTacToe(Context c) {
            super(c);
            reset();
        }

        private void reset() {
            java.util.Arrays.fill(b, 0);
            result = 0;
            status = "Tú juegas con X";
        }

        private static int winner(int[] s) {
            int[][] lines = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
            for (int[] l : lines) if (s[l[0]] != 0 && s[l[0]] == s[l[1]] && s[l[1]] == s[l[2]]) return s[l[0]];
            for (int v : s) if (v == 0) return 0;
            return 3;
        }

        /** Minimax: puntuación para el móvil (2). */
        private int score(int[] s, boolean cpuTurn, int depth) {
            int w = winner(s);
            if (w == 2) return 10 - depth;
            if (w == 1) return depth - 10;
            if (w == 3) return 0;
            int best = cpuTurn ? -100 : 100;
            for (int i = 0; i < 9; i++) {
                if (s[i] != 0) continue;
                s[i] = cpuTurn ? 2 : 1;
                int v = score(s, !cpuTurn, depth + 1);
                s[i] = 0;
                best = cpuTurn ? Math.max(best, v) : Math.min(best, v);
            }
            return best;
        }

        private void cpu() {
            int best = -1;
            int bestScore = -1000;
            for (int i = 0; i < 9; i++) {
                if (b[i] != 0) continue;
                b[i] = 2;
                int v = score(b, false, 0);
                b[i] = 0;
                if (v > bestScore) {
                    bestScore = v;
                    best = i;
                }
            }
            if (best >= 0) b[best] = 2;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (result != 0) {
                reset();
                invalidate();
                return true;
            }
            for (int i = 0; i < 9; i++) {
                if (b[i] == 0 && cellRect(3, i / 3, i % 3, 8).contains(e.getX(), e.getY())) {
                    b[i] = 1;
                    result = winner(b);
                    if (result == 0) {
                        cpu();
                        result = winner(b);
                    }
                    status = result == 1 ? "¡Has ganado! Toca para otra" : result == 2 ? "Gana el móvil. Toca para otra"
                            : result == 3 ? "Empate. Toca para otra" : "Tu turno";
                    invalidate();
                    break;
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            for (int i = 0; i < 9; i++) {
                String s = b[i] == 1 ? "X" : b[i] == 2 ? "O" : null;
                cell(cv, cellRect(3, i / 3, i % 3, 8), 0xFF3A3F46, s, b[i] == 1 ? CarStyle.ACCENT : 0xFFF28B82, 150);
            }
            drawStatus(cv);
        }
    }

    @Override
    public void destroy() {
    }
}
