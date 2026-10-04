package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Juegos sencillos para las esperas con el coche parado: 2048, memoria, tres en raya, Autonomía y coche volador. */
final class GamesScreen implements CarScreen {
    private Host host;
    private View menu;

    private Board current;

    private static final int[][] GAMES = {
            {R.string.hql_game_2048, R.string.hql_game_2048_sub},
            {R.string.hql_game_memory, R.string.hql_game_memory_sub},
            {R.string.hql_game_ttt, R.string.hql_game_ttt_sub},
            {R.string.hql_game_range, R.string.hql_game_range_sub},
            {R.string.hql_game_fly, R.string.hql_game_fly_sub}};
    private static final int[] ICONS = {R.drawable.hql_ic_game_2048, R.drawable.hql_ic_game_memory, R.drawable.hql_ic_game_ttt,
            R.drawable.hql_ic_game_range, R.drawable.hql_ic_game_fly};
    private static final int[] TINTS = {0xFFFDD663, 0xFF81C995, 0xFF8AB4F8, 0xFFF6AEA9, 0xFFC58AF9};

    private boolean running;
    /** Solo UiPreview: abrir este juego en modo demostración al crear la pantalla (-1: menú). */
    static int previewGame = -1;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < GAMES.length; i++) {
            int idx = i;
            LinearLayout card = CarStyle.card(c);
            card.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(c);
            icon.setImageResource(ICONS[i]);
            icon.setImageTintList(ColorStateList.valueOf(0xFF202124));
            icon.setBackground(CarStyle.round(TINTS[i], 60));
            icon.setPadding(26, 26, 26, 26);
            card.addView(icon, new LinearLayout.LayoutParams(120, 120));
            TextView title = CarStyle.text(c, Str.get(GAMES[i][0]), 34, CarStyle.TEXT);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setPadding(0, 26, 0, 6);
            title.setGravity(Gravity.CENTER);
            card.addView(title);
            TextView sub = CarStyle.text(c, Str.get(GAMES[i][1]), 22, CarStyle.TEXT_DIM);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(272, 330);
            lp.setMargins(10, 0, 10, 0);
            row.addView(card, lp);
            card.setOnClickListener(v -> open(idx));
        }
        menu = row;
        if (previewGame >= 0) {
            int g = previewGame;
            previewGame = -1;
            h.post(() -> {
                open(g);
                if (current != null) current.demo();
            });
        }
        return row;
    }

    private void open(int game) {
        Context c = host.context();
        Board board = game == 0 ? new Game2048(c) : game == 1 ? new Memory(c) : game == 2 ? new TicTacToe(c)
                : game == 3 ? new Range(c) : new Flappy(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(24, 18, 24, 6);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_games));
        TextView title = CarStyle.text(c, Str.get(GAMES[game][0]), 30, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(16, 0, 24, 0);
        TextView status = CarStyle.text(c, "", 24, CarStyle.TEXT_DIM);
        TextView again = CarStyle.pill(c, Str.get(R.string.hql_new_game));
        header.addView(back);
        header.addView(title);
        header.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(again);
        col.addView(header);
        col.addView(board, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        back.setOnClickListener(v -> {
            running = false;
            host.setContent(menu);
        });
        again.setOnClickListener(v -> {
            board.reset();
            board.invalidate();
        });
        running = true;
        current = board;
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!running) return;
            status.setText(board.status);
            board.postDelayed(poll[0], 250);
        };
        poll[0].run();
        host.setContent(col);
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
            float side = Math.min(w, h) - 40;
            board.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
        }

        /** Nueva partida. */
        abstract void reset();

        /** Solo UiPreview: un estado de juego que enseñe algo. */
        void demo() {
        }

        /** El estado se muestra en la cabecera (ver open). */
        void drawStatus(Canvas cv) {
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

        void reset() {
            for (int[] r : g) java.util.Arrays.fill(r, 0);
            score = 0;
            spawn();
            spawn();
            status = Str.get(R.string.hql_points, 0);
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
                    status = canMove() ? Str.get(R.string.hql_points, score) : Str.get(R.string.hql_2048_over, score);
                }
                invalidate();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            cell(cv, board, CarStyle.CARD, null, 0, 0);
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                int v = g[r][c];
                int lvl = v == 0 ? 0 : Integer.numberOfTrailingZeros(v);
                int fill = v == 0 ? 0xFF3C4043 : android.graphics.Color.HSVToColor(new float[]{(40 - lvl * 9 + 360) % 360, 0.55f, 0.45f + Math.min(lvl, 11) * 0.04f});
                cell(cv, cellRect(4, r, c, 8), fill, v == 0 ? null : String.valueOf(v), CarStyle.TEXT, v < 1000 ? 64 : 48);
            }
            drawStatus(cv);
        }
    }

    // ------------------------------------------------------------------ memoria

    private static final class Memory extends Board {
        /** "C10": guiño al coche, dibujado de frente (CarArt), sin texto. */
        private static final String[] SYMBOLS = {"★", "♥", "♦", "⚡", "🔋", "☀", "☂", "♫", "✈", "C10"};
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

        void reset() {
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
            status = Str.get(R.string.hql_moves, 0);
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
                    status = fin ? Str.get(R.string.hql_memory_done, moves) : Str.get(R.string.hql_moves, moves);
                }
                invalidate();
                break;
            }
            return true;
        }

        @Override
        void demo() {
            java.util.Arrays.fill(open, true);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            for (int i = 0; i < cards.size(); i++) {
                boolean shown = open[i] || done[i];
                boolean c10 = shown && "C10".equals(cards.get(i));
                RectF r = rect(i);
                cell(cv, r, done[i] ? 0xFF2B4A3A : shown ? 0xFF3A4A66 : 0xFF3C4043,
                        shown && !c10 ? cards.get(i) : null, CarStyle.TEXT, 72);
                if (c10) CarArt.front(cv, r, p);
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

        void reset() {
            java.util.Arrays.fill(b, 0);
            result = 0;
            status = Str.get(R.string.hql_ttt_start);
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
                    status = result == 1 ? Str.get(R.string.hql_ttt_win) : result == 2 ? Str.get(R.string.hql_ttt_lose)
                            : result == 3 ? Str.get(R.string.hql_ttt_draw) : Str.get(R.string.hql_ttt_turn);
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
                cell(cv, cellRect(3, i / 3, i % 3, 8), 0xFF3C4043, s, b[i] == 1 ? CarStyle.ACCENT : 0xFFF28B82, 150);
            }
            drawStatus(cv);
        }
    }

    // ------------------------------------------------------------------ autonomía

    /**
     * Autonomía: el coche (visto desde arriba) por una carretera de tres carriles. Toca a la izquierda o
     * a la derecha para cambiar de carril, esquiva conos y coches y coge los puntos de carga. Sin
     * cargar, la batería da justo para los 420 km WLTP del coche; pasar de ahí tiene premio.
     */
    private static final class Range extends Board {
        private static final float PX_PER_KM = 400f;
        private static final float WLTP_KM = 420f;
        private static final int CONE = 0;
        private static final int CAR = 1;
        private static final int CHARGER = 2;
        private final Random rnd = new Random();
        private final List<float[]> items = new ArrayList<>(); // {carril, y, tipo}
        private final RectF road = new RectF();
        private int lane;
        private float laneX;
        private float km;
        private float battery;
        private float nextSpawn;
        private float scroll;
        private boolean over;
        private long lastNs;
        private long bannerUntil;
        private String banner;
        private boolean passedWltp;

        Range(Context c) {
            super(c);
            reset();
        }

        void reset() {
            items.clear();
            lane = 1;
            laneX = 1;
            km = 0;
            battery = 100;
            nextSpawn = 0;
            over = false;
            passedWltp = false;
            banner = Str.get(R.string.hql_range_help);
            bannerUntil = android.os.SystemClock.uptimeMillis() + 3000;
            lastNs = 0;
            status = Str.get(R.string.hql_range_status, 0f, 100f);
            postInvalidateOnAnimation();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float rw = Math.min(w - 40, h * 0.9f);
            road.set((w - rw) / 2f, 0, (w + rw) / 2f, h);
        }

        private float laneW() {
            return road.width() / 3f;
        }

        private float carW() {
            return laneW() * 0.52f;
        }

        private float carH() {
            return carW() * 2.05f;
        }

        private void step(float dt) {
            float speed = Math.min(1700f, 650f + km * 2.5f); // px/s
            float d = speed * dt;
            scroll = (scroll + d) % 120f;
            km += d / PX_PER_KM;
            battery -= d / PX_PER_KM * (100f / WLTP_KM);
            laneX += (lane - laneX) * Math.min(1f, dt * 14f);
            nextSpawn -= d;
            if (nextSpawn <= 0) {
                int type = rnd.nextInt(10) < 2 ? CHARGER : rnd.nextBoolean() ? CONE : CAR;
                items.add(new float[]{rnd.nextInt(3), -carH(), type});
                // A veces un segundo obstáculo, siempre dejando un carril libre.
                if (type != CHARGER && rnd.nextInt(4) == 0) {
                    int other = ((int) items.get(items.size() - 1)[0] + 1 + rnd.nextInt(2)) % 3;
                    items.add(new float[]{other, -carH(), rnd.nextBoolean() ? CONE : CAR});
                }
                nextSpawn = carH() * (1.6f + rnd.nextFloat() * 1.4f);
            }
            float carTop = road.bottom - carH() - 40;
            for (int i = items.size() - 1; i >= 0; i--) {
                float[] it = items.get(i);
                // Los coches van en tu sentido, algo más lentos: se acercan menos deprisa.
                it[1] += it[2] == CAR ? d * 0.55f : d;
                if (it[1] > road.bottom + carH()) {
                    items.remove(i);
                    continue;
                }
                float ih = it[2] == CAR ? carH() : carW();
                boolean sameLane = Math.round(it[0]) == Math.round(laneX);
                if (sameLane && it[1] + ih > carTop + 10 && it[1] < carTop + carH() - 10) {
                    if (it[2] == CHARGER) {
                        battery = Math.min(100f, battery + 18f);
                        items.remove(i);
                        say(Str.get(R.string.hql_range_charge));
                    } else {
                        finish(it[2] == CONE ? Str.get(R.string.hql_range_cone) : Str.get(R.string.hql_range_crash));
                        return;
                    }
                }
            }
            if (!passedWltp && km >= WLTP_KM) {
                passedWltp = true;
                say(Str.get(R.string.hql_range_wltp));
            }
            if (battery <= 0) {
                battery = 0;
                finish(km >= WLTP_KM ? Str.get(R.string.hql_range_empty_wltp) : Str.get(R.string.hql_range_empty));
                return;
            }
            status = Str.get(R.string.hql_range_status, km, battery);
        }

        private void say(String s) {
            banner = s;
            bannerUntil = android.os.SystemClock.uptimeMillis() + 2500;
        }

        private void finish(String why) {
            over = true;
            status = Str.get(R.string.hql_range_over, why, km);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            if (over) {
                reset();
                return true;
            }
            lane = Math.max(0, Math.min(2, lane + (e.getX() < getWidth() / 2f ? -1 : 1)));
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            long now = System.nanoTime();
            if (!over && lastNs != 0) step(Math.min(0.05f, (now - lastNs) / 1e9f));
            lastNs = now;
            // Carretera y marcas de carril que avanzan.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2C30);
            cv.drawRoundRect(road, 24, 24, p);
            p.setColor(0xFF5F6368);
            for (int l = 1; l < 3; l++) {
                float x = road.left + l * laneW();
                for (float y = -120 + scroll; y < road.bottom; y += 120) cv.drawRect(x - 4, y, x + 4, y + 60, p);
            }
            float lw = laneW();
            for (float[] it : items) {
                float cx = road.left + (it[0] + 0.5f) * lw;
                if (it[2] == CONE) drawCone(cv, cx, it[1] + carW() / 2);
                else if (it[2] == CHARGER) drawCharger(cv, cx, it[1] + carW() / 2);
                else drawOtherCar(cv, new RectF(cx - carW() / 2, it[1], cx + carW() / 2, it[1] + carH()));
            }
            float cx = road.left + (laneX + 0.5f) * lw;
            float top = road.bottom - carH() - 40;
            CarArt.top(cv, new RectF(cx - carW() / 2, top, cx + carW() / 2, top + carH()), p);
            // Batería.
            RectF bat = new RectF(road.right + 30, road.top + 40, road.right + 70, road.top + 300);
            if (bat.right < getWidth()) {
                p.setColor(0xFF3C4043);
                cv.drawRoundRect(bat, 10, 10, p);
                p.setColor(battery > 30 ? CarStyle.GOOD : battery > 12 ? CarStyle.WARN : CarStyle.BAD);
                float fh = (bat.height() - 8) * battery / 100f;
                cv.drawRoundRect(new RectF(bat.left + 4, bat.bottom - 4 - fh, bat.right - 4, bat.bottom - 4), 7, 7, p);
            }
            if (banner != null && android.os.SystemClock.uptimeMillis() < bannerUntil) {
                p.setTextSize(34);
                p.setTextAlign(Paint.Align.CENTER);
                float tw = p.measureText(banner) + 48;
                RectF b = new RectF(road.centerX() - tw / 2, 30, road.centerX() + tw / 2, 90);
                p.setColor(0xE6202124);
                cv.drawRoundRect(b, 30, 30, p);
                p.setColor(CarStyle.TEXT);
                cv.drawText(banner, b.centerX(), b.centerY() + 12, p);
            }
            if (!over && isAttachedToWindow()) postInvalidateOnAnimation();
        }

        private void drawCone(Canvas cv, float cx, float cy) {
            float s = carW() * 0.42f;
            android.graphics.Path t = new android.graphics.Path();
            t.moveTo(cx, cy - s);
            t.lineTo(cx + s * 0.75f, cy + s * 0.8f);
            t.lineTo(cx - s * 0.75f, cy + s * 0.8f);
            t.close();
            p.setColor(0xFFFF8A00);
            cv.drawPath(t, p);
            p.setColor(0xFFF1F3F4);
            cv.drawRect(cx - s * 0.38f, cy, cx + s * 0.38f, cy + s * 0.18f, p);
        }

        private void drawCharger(Canvas cv, float cx, float cy) {
            float s = carW() * 0.45f;
            p.setColor(CarStyle.GOOD);
            cv.drawCircle(cx, cy, s, p);
            android.graphics.Path b = new android.graphics.Path();
            b.moveTo(cx + s * 0.12f, cy - s * 0.7f);
            b.lineTo(cx - s * 0.45f, cy + s * 0.1f);
            b.lineTo(cx - s * 0.02f, cy + s * 0.1f);
            b.lineTo(cx - s * 0.12f, cy + s * 0.7f);
            b.lineTo(cx + s * 0.45f, cy - s * 0.1f);
            b.lineTo(cx + s * 0.02f, cy - s * 0.1f);
            b.close();
            p.setColor(0xFF0B1D36);
            cv.drawPath(b, p);
        }

        private void drawOtherCar(Canvas cv, RectF r) {
            p.setColor(0xFF6B7280);
            cv.drawRoundRect(r, r.width() * 0.25f, r.width() * 0.25f, p);
            p.setColor(0xFF374151);
            cv.drawRoundRect(new RectF(r.left + r.width() * 0.15f, r.top + r.height() * 0.28f, r.right - r.width() * 0.15f,
                    r.bottom - r.height() * 0.18f), 12, 12, p);
            p.setColor(0xFFE53935);
            cv.drawRect(r.left + r.width() * 0.08f, r.bottom - r.height() * 0.05f, r.left + r.width() * 0.3f, r.bottom - r.height() * 0.02f, p);
            cv.drawRect(r.right - r.width() * 0.3f, r.bottom - r.height() * 0.05f, r.right - r.width() * 0.08f, r.bottom - r.height() * 0.02f, p);
        }
    }

    // ------------------------------------------------------------------ coche volador

    /**
     * Coche volador (tipo Flappy Bird): el coche de perfil cruza entre postes de carga; cada toque le da
     * un impulso. Récord mientras dure la sesión; guiños al coche a los 10 y 70 postes (69,9 kWh de batería).
     */
    private static final class Flappy extends Board {
        private static int best;
        private final Random rnd = new Random();
        private final List<float[]> posts = new ArrayList<>(); // {x, centro del hueco, puntuado}
        private float y;
        private float vy;
        private int score;
        private boolean started;
        private boolean over;
        private long lastNs;
        private float groundScroll;
        private String banner;
        private long bannerUntil;

        Flappy(Context c) {
            super(c);
            reset();
        }

        void reset() {
            posts.clear();
            y = getHeight() > 0 ? getHeight() * 0.45f : 300;
            vy = 0;
            score = 0;
            started = false;
            over = false;
            lastNs = 0;
            banner = null;
            status = Str.get(R.string.hql_fly_start) + (best > 0 ? " · " + Str.get(R.string.hql_record, best) : "");
            postInvalidateOnAnimation();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (!started) y = h * 0.45f;
        }

        private float carW() {
            return Math.min(getWidth() * 0.12f, getHeight() * 0.24f);
        }

        private float carH() {
            return carW() * 0.42f;
        }

        private float gap() {
            return getHeight() * 0.34f;
        }

        private float postW() {
            return carW() * 0.8f;
        }

        private float ground() {
            return getHeight() - 36;
        }

        private void step(float dt) {
            float h = getHeight();
            vy += h * 2.6f * dt;
            y += vy * dt;
            float speed = getWidth() * (0.30f + Math.min(score, 30) * 0.004f);
            groundScroll = (groundScroll + speed * dt) % 80f;
            float last = posts.isEmpty() ? 0 : posts.get(posts.size() - 1)[0];
            if (posts.isEmpty() || last < getWidth() - getWidth() * 0.36f) {
                float margin = gap() / 2 + 40;
                posts.add(new float[]{getWidth() + postW(), margin + rnd.nextFloat() * (ground() - 2 * margin), 0});
            }
            float cx = getWidth() * 0.25f;
            for (int i = posts.size() - 1; i >= 0; i--) {
                float[] pt = posts.get(i);
                pt[0] -= speed * dt;
                if (pt[0] < -postW()) {
                    posts.remove(i);
                    continue;
                }
                if (pt[2] == 0 && pt[0] + postW() < cx - carW() / 2) {
                    pt[2] = 1;
                    score++;
                    if (score == 10) say(Str.get(R.string.hql_fly_10));
                    else if (score == 70) say(Str.get(R.string.hql_fly_70));
                }
                boolean overlapX = cx + carW() / 2 - 8 > pt[0] && cx - carW() / 2 + 8 < pt[0] + postW();
                boolean inGap = y - carH() / 2 + 6 > pt[1] - gap() / 2 && y + carH() / 2 - 6 < pt[1] + gap() / 2;
                if (overlapX && !inGap) {
                    finish();
                    return;
                }
            }
            if (y + carH() / 2 > ground() || y - carH() / 2 < 0) {
                finish();
                return;
            }
            status = Str.get(R.string.hql_fly_posts, score) + (best > 0 ? " · " + Str.get(R.string.hql_record, best) : "");
        }

        private void say(String s) {
            banner = s;
            bannerUntil = android.os.SystemClock.uptimeMillis() + 2500;
        }

        private void finish() {
            over = true;
            boolean record = score > best;
            best = Math.max(best, score);
            status = (record && score > 0 ? Str.get(R.string.hql_new_record) + " " : Str.get(R.string.hql_game_over) + " · ") + Str.get(R.string.hql_fly_over, score);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            if (over) {
                reset();
                return true;
            }
            started = true;
            vy = -getHeight() * 0.95f;
            return true;
        }

        @Override
        void demo() {
            started = true;
            postDelayed(() -> vy = -getHeight() * 0.95f, 300);
            postDelayed(() -> vy = -getHeight() * 0.95f, 700);
        }

        @Override
        protected void onDraw(Canvas cv) {
            long now = System.nanoTime();
            if (started && !over && lastNs != 0) step(Math.min(0.05f, (now - lastNs) / 1e9f));
            lastNs = now;
            float w = getWidth();
            float h = getHeight();
            p.setStyle(Paint.Style.FILL);
            // Cielo de noche con degradado suave.
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF1B2A41, 0xFF2D3B50, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRoundRect(new RectF(0, 0, w, h), 24, 24, p);
            p.setShader(null);
            // Postes de carga: columna gris con piloto verde junto al hueco.
            for (float[] pt : posts) {
                float top = pt[1] - gap() / 2;
                float bot = pt[1] + gap() / 2;
                p.setColor(0xFF9AA0A6);
                cv.drawRoundRect(new RectF(pt[0], -20, pt[0] + postW(), top), 14, 14, p);
                cv.drawRoundRect(new RectF(pt[0], bot, pt[0] + postW(), ground()), 14, 14, p);
                p.setColor(CarStyle.GOOD);
                cv.drawRoundRect(new RectF(pt[0] + postW() * 0.2f, top - 22, pt[0] + postW() * 0.8f, top - 12), 5, 5, p);
                cv.drawRoundRect(new RectF(pt[0] + postW() * 0.2f, bot + 12, pt[0] + postW() * 0.8f, bot + 22), 5, 5, p);
            }
            // Suelo.
            p.setColor(0xFF3C4043);
            cv.drawRect(0, ground(), w, h, p);
            p.setColor(0xFF5F6368);
            for (float x = -groundScroll; x < w; x += 80) cv.drawRect(x, ground() + 14, x + 40, ground() + 20, p);
            // El coche, inclinado según suba o caiga.
            float cx = w * 0.25f;
            cv.save();
            cv.rotate(Math.max(-20f, Math.min(35f, vy / h * 30f)), cx, y);
            CarArt.side(cv, new RectF(cx - carW() / 2, y - carH() / 2, cx + carW() / 2, y + carH() / 2), p);
            cv.restore();
            if (banner != null && android.os.SystemClock.uptimeMillis() < bannerUntil) {
                p.setTextSize(34);
                p.setTextAlign(Paint.Align.CENTER);
                float tw = p.measureText(banner) + 48;
                RectF b = new RectF(w / 2 - tw / 2, 30, w / 2 + tw / 2, 90);
                p.setColor(0xE6202124);
                cv.drawRoundRect(b, 30, 30, p);
                p.setColor(CarStyle.TEXT);
                cv.drawText(banner, b.centerX(), b.centerY() + 12, p);
            }
            if (!over && isAttachedToWindow()) postInvalidateOnAnimation();
        }
    }

    @Override
    public void destroy() {
        running = false;
    }
}
