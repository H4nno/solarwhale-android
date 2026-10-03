package de.solarwhale.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SolarWhale native app: dashboard + analysis chart + settings, swipe tabs. */
public class MainActivity extends Activity {

    private static final String PREFS = "solarwhale";
    private static final int C_TEXT = 0xFFE9EFFA, C_DIM = 0xFF8F9CB5,
            C_ACCENT = 0xFF6DB3FF, C_WARN = 0xFFE9B44D,
            C_CARD = 0x9E101626, C_BORDER = 0x2A94B2FF, C_BG = 0xFF04060C,
            C_BARBG = 0x1E94B2FF, C_FAINT = 0xFF5D6A84;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String baseUrl = "";
    private int tab = 0;
    private LinearLayout pages;
    private final TextView[] tabViews = new TextView[3];
    private final View[] pageViews = new View[3];
    private volatile JSONObject status, history;
    private float touchX, touchY; private long touchT;

    private TextView vSoc, vPv, vGrid, vHaus, vMiner, vYield,
            nBatt, nGrid, nMiner, nWx, nYield, dailyNote;
    private ChartView chart;
    private LinearLayout settingsList;
    private final Map<String, EditText> fields = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        baseUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString("url", "");
        if (baseUrl.isEmpty()) { discover(); return; }
        buildUi();
        poll();
    }

    // ---------- discovery ----------

    private void discover() {
        LinearLayout root = splash("Suche Server (solar.local / fritz.box)…");
        setContentView(root);
        pool.execute(() -> {
            String found = null;
            for (String h : new String[]{"solar.local", "solarwhale.local",
                    "solar.fritz.box", "solarwhale.fritz.box"}) {
                try {
                    InetAddress a = InetAddress.getByName(h);
                    found = "http://" + a.getHostAddress() + ":8080";
                    break;
                } catch (Exception ignore) { }
            }
            final String f = found;
            ui.post(() -> {
                if (f != null) { saveAndStart(f); return; }
                TextView st = (TextView) root.getChildAt(1);
                st.setText("Kein Server gefunden. Adresse eingeben:");
                final EditText in = new EditText(MainActivity.this);
                in.setHint("http://192.168.1.20:8080");
                in.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
                in.setTextColor(C_TEXT); in.setHintTextColor(C_FAINT);
                root.addView(in, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView go = new TextView(this);
                go.setText("Verbinden"); go.setTextColor(C_ACCENT);
                go.setTextSize(16); go.setGravity(Gravity.CENTER);
                go.setPadding(0, dp(16), 0, 0);
                go.setOnClickListener(v -> {
                    String u = in.getText().toString().trim();
                    if (!u.startsWith("http")) u = "http://" + u;
                    if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    saveAndStart(u);
                });
                root.addView(go);
            });
        });
    }

    private void saveAndStart(String url) {
        baseUrl = url;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("url", url).apply();
        buildUi();
        poll();
    }

    private LinearLayout splash(String msg) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(C_BG);
        TextView t = new TextView(this);
        t.setText("SolarWhale"); t.setTextColor(C_TEXT);
        t.setTextSize(26); t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        TextView st = new TextView(this);
        st.setText(msg); st.setTextColor(C_DIM); st.setTextSize(13);
        st.setGravity(Gravity.CENTER); st.setPadding(0, dp(16), 0, dp(16));
        root.addView(st);
        return root;
    }

    // ---------- ui ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(18), dp(14), dp(8));

        TextView title = new TextView(this);
        title.setText("SolarWhale"); title.setTextColor(C_TEXT);
        title.setTextSize(22); title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        LinearLayout tabsRow = new LinearLayout(this);
        String[] names = {"Dashboard", "Analyse", "Einstellungen"};
        for (int i = 0; i < 3; i++) {
            TextView tv = new TextView(this);
            tv.setText(names[i]); tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(6), dp(10), dp(6), dp(10));
            final int idx = i;
            tv.setOnClickListener(v -> selectTab(idx));
            tabsRow.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabViews[i] = tv;
        }
        root.addView(tabsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pages = new LinearLayout(this);
        pageViews[0] = dashPage();
        pageViews[1] = analysePage();
        pageViews[2] = settingsPage();
        for (int i = 0; i < 3; i++) {
            pages.addView(pageViews[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pageViews[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }
        root.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        selectTab(0);
    }

    private void selectTab(int n) {
        tab = n;
        String[] names = {"Dashboard", "Analyse", "Einstellungen"};
        for (int i = 0; i < 3; i++) {
            pageViews[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
            tabViews[i].setTextColor(i == n ? C_TEXT : C_DIM);
        }
        if (n == 1) loadHistory();
    }

    private TextView label(String text, boolean caps) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_DIM); tv.setTextSize(11);
        tv.setAllCaps(caps);
        return tv;
    }

    private LinearLayout card(View... children) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(C_CARD);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        for (View v : children) c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView big(String tag) {
        TextView tv = new TextView(this);
        tv.setTag("v_" + tag);
        tv.setTextColor(C_TEXT); tv.setTextSize(26); tv.setTypeface(null, Typeface.BOLD);
        tv.setText("–");
        return tv;
    }

    private TextView note(String tag) {
        TextView tv = new TextView(this);
        tv.setTag("n_" + tag);
        tv.setTextColor(C_DIM); tv.setTextSize(11);
        tv.setText("");
        return tv;
    }

    private View dashPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        sc.addView(p, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vSoc = big("soc"); nBatt = note("batt");
        p.addView(card(label("Batterie (SOC)", true), vSoc, nBatt));
        vPv = big("pv");
        p.addView(card(label("PV-Erzeugung", true), vPv));
        vGrid = big("grid"); nGrid = note("grid");
        p.addView(card(label("Netz", true), vGrid, nGrid));
        vHaus = big("haus");
        p.addView(card(label("Hausverbrauch", true), vHaus));
        p.addView(minerCard());
        nWx = note("wx");
        p.addView(card(label("Sonne & PV-Prognose", true), nWx));
        vYield = big("yield"); nYield = note("yield");
        p.addView(card(label("Ertrag heute", true), vYield, nYield));
        return sc;
    }

    private View minerCard() {
        vMiner = big("miner"); nMiner = note("miner");
        LinearLayout c = card(label("Bitcoin-Miner", true), vMiner, nMiner);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundColor(C_BARBG);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        slp.topMargin = dp(8);
        String[] names = {"Automatik", "EIN", "AUS"};
        final String[] modes = {"auto", "on", "off"};
        for (int i = 0; i < 3; i++) {
            TextView tv = new TextView(this);
            tv.setText(names[i]); tv.setGravity(Gravity.CENTER);
            tv.setTextColor(C_DIM); tv.setTextSize(13);
            final String mode = modes[i];
            tv.setOnClickListener(v -> setManual(mode));
            seg.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        c.addView(seg, slp);
        return c;
    }

    private View analysePage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        chart = new ChartView();
        LinearLayout holder = new LinearLayout(this);
        holder.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        p.addView(card(label("Einspeisung 24h — grün = Miner AN", true), holder));
        dailyNote = note("daily");
        p.addView(card(label("Kennzahlen (24h)", true), dailyNote));
        sc.addView(p, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private View settingsPage() {
        ScrollView sc = new ScrollView(this);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        sc.addView(settingsList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView save = new TextView(this);
        save.setText("Speichern"); save.setTextColor(C_ACCENT); save.setTextSize(15);
        save.setGravity(Gravity.CENTER); save.setPadding(0, dp(14), 0, dp(6));
        save.setOnClickListener(v -> saveSettings());
        settingsList.addView(save);
        TextView server = new TextView(this);
        server.setText("Server: " + baseUrl);
        server.setTextColor(C_FAINT); server.setTextSize(11);
        server.setTag("server");
        settingsList.addView(server);
        return sc;
    }

    private void fillSettings() {
        if (status == null || settingsList == null) return;
        JSONObject f = status.optJSONObject("fields");
        if (f == null) return;
        settingsList.removeViews(0, Math.max(0, settingsList.getChildCount() - 2));
        fields.clear();
        String[][] specs = {
                {"export_on_above", "Einspeisung ab W → AN"},
                {"house_on_export_below", "Haus unter W"},
                {"soc_on", "SOC über % → AN"},
                {"house_on_mine_below", "Haus unter W"},
                {"soc_off", "SOC unter % → AUS"},
                {"house_off_above", "Haus über W → AUS"},
                {"min_off_min", "Pause nach AUS (Min)"},
                {"interval_s", "Intervall (s)"},
                {"miner_w", "Miner-Aufnahme W"},
                {"mining_wert", "Mining-Wert €/kWh"},
                {"einspeise_tarif", "Einspeisung €/kWh"},
                {"strompreis", "Strompreis €/kWh"},
                {"lat", "Breitengrad"},
                {"lon", "Längengrad"},
                {"pv_kwp", "PV-Peak kWp (optional)"},
                {"miner_hosts", "Miner-IP(s) (Komma, leer = Scan)"},
        };
        int at = 0;
        for (String[] spec : specs) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(label(spec[1], false));
            EditText et = new EditText(this);
            et.setTextColor(C_TEXT); et.setTextSize(14);
            et.setBackgroundResource(android.R.color.transparent);
            et.setText(f.optString(spec[0], ""));
            et.setTag(spec[0]);
            wrap.addView(et);
            fields.put(spec[0], et);
            settingsList.addView(wrap, at++,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView server = findServer();
        if (server != null) server.setText("Server: " + baseUrl);
    }

    private TextView findServer() {
        for (int i = settingsList.getChildCount() - 1; i >= 0; i--) {
            View v = settingsList.getChildAt(i);
            if ("server".equals(v.getTag())) return (TextView) v;
        }
        return null;
    }

    private void saveSettings() {
        pool.execute(() -> {
            try {
                JSONObject out = new JSONObject();
                for (Map.Entry<String, EditText> e : fields.entrySet()) {
                    String val = e.getValue().getText().toString().trim();
                    String k = e.getKey();
                    if (k.equals("miner_hosts")) {
                        out.put(k, val);
                    } else if (val.isEmpty()) {
                        out.put(k, 0);
                    } else {
                        out.put(k, Double.parseDouble(val.replace(",", ".")));
                    }
                }
                httpPost(baseUrl + "/api/config", out.toString());
                refresh();
            } catch (Exception ignore) { }
        });
    }

    private void setManual(String mode) {
        pool.execute(() -> {
            try {
                httpPost(baseUrl + "/api/manual",
                        new JSONObject().put("mode", mode).toString());
                refresh();
            } catch (Exception ignore) { }
        });
    }

    // ---------- data ----------

    private void poll() {
        pool.execute(() -> {
            try { status = new JSONObject(httpGet(baseUrl + "/api/status")); }
            catch (Exception e) { status = null; }
            ui.post(() -> { render(); fillSettings(); });
            ui.postDelayed(this::poll, 12000);
        });
    }

    private void refresh() {
        pool.execute(() -> {
            try {
                status = new JSONObject(httpGet(baseUrl + "/api/status"));
                ui.post(this::render);
            } catch (Exception ignore) { }
        });
    }

    private void loadHistory() {
        pool.execute(() -> {
            try {
                history = new JSONObject(httpGet(baseUrl + "/api/history?hours=24"));
                ui.post(this::renderChart);
            } catch (Exception ignore) { }
        });
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    private String httpPost(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.getOutputStream().write(body.getBytes("UTF-8"));
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    // ---------- render ----------

    private void render() {
        if (status == null) return;
        JSONObject m = status.optJSONObject("metrics");
        JSONObject st = status.optJSONObject("stats");
        JSONObject pr = status.optJSONObject("prices");
        if (m == null) return;
        vSoc.setText(fmtNum(m.optDouble("soc", 0)) + " %");
        vPv.setText(fmtW(m.optDouble("pv_w", 0)) + " W");
        vGrid.setText(fmtW(-1 * m.optDouble("grid_power_w", 0)) + " W");
        vHaus.setText(fmtW(m.optDouble("house_load_w", 0)) + " W");
        boolean on = status.optBoolean("miner_on")
                || "on".equals(status.optString("manual"));
        vMiner.setText(on ? "RUNNING" : "STOPPED");
        double bw = m.optDouble("batt_w", 0);
        String bs = bw >= 0 ? "lädt " : "entlädt ";
        double a = Math.abs(bw);
        bs += a >= 1000 ? String.format("%.1f kW", a / 1000) : String.format("%.0f W", a);
        if (!m.isNull("batt_temp_c")) bs += String.format(" · %.0f °C",
                m.optDouble("batt_temp_c", 0));
        nBatt.setText(bs);
        double exp = -1 * m.optDouble("grid_power_w", 0);
        nGrid.setText(exp > 30 ? "⚡ Strom geht ins Netz!" : "");
        JSONObject mn = status.optJSONObject("miner");
        if (mn != null && mn.optBoolean("ok")) {
            String s = fmtNum(mn.optDouble("hashrate_ths", 0)) + " TH/s";
            if (!mn.isNull("power_w")) s += " · " + fmtW(mn.optDouble("power_w", 0)) + " W";
            nMiner.setText(s);
        } else nMiner.setText("keine Telemetrie");
        JSONObject wx = status.optJSONObject("weather");
        StringBuilder sb = new StringBuilder();
        if (wx != null) {
            JSONArray days = wx.optJSONArray("days");
            JSONObject model = wx.optJSONObject("model");
            if (days != null) for (int i = 0; i < Math.min(5, days.length()); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                sb.append(d.optString("date", "").substring(5))
                  .append(": ☀").append(fmtNum(d.optDouble("sun_h", 0))).append("h");
                if (model != null) {
                    double eur = Math.max(0, model.optDouble("a", 0)
                            * d.optDouble("rad_kwh", 0) + model.optDouble("b", 0)) * 0.07;
                    sb.append(String.format(" (%.2f €)", eur));
                }
                sb.append("\n");
            }
        }
        nWx.setText(sb.length() > 0 ? sb.toString() : "lade Wetter…");
        double minerW = status.optDouble("miner_w", 2200);
        double kwh = (st == null ? 0 : st.optDouble("on_s", 0)) / 3600.0 * minerW / 1000.0;
        double eur = kwh * (pr == null ? 0.07 : pr.optDouble("mining_value", 0.07));
        String cur = pr == null ? "€" : pr.optString("currency", "€");
        vYield.setText(String.format("%.2f %s", eur, cur));
        nYield.setText(String.format("%.0f W × %.1f h = %.1f kWh",
                minerW, st == null ? 0 : st.optDouble("on_s", 0) / 3600.0, kwh));
    }

    private void renderChart() {
        if (history == null || chart == null) return;
        chart.setData(history);
        JSONObject sum = history.optJSONObject("summary");
        if (dailyNote != null && sum != null) {
            JSONObject pr = history.optJSONObject("prices");
            String cur = pr == null ? "€" : pr.optString("currency", "€");
            dailyNote.setText(String.format(
                    "eingespeist: %.1f kWh · entgangen: %.2f %s · Miner: %.1f h · Bezug: %.1f kWh",
                    sum.optDouble("export_kwh", 0), sum.optDouble("unused_value", 0), cur,
                    sum.optDouble("miner_h", 0), sum.optDouble("import_kwh", 0)));
        }
    }

    private String fmtNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.format("%d", (long) v);
        return String.format("%.1f", v).replace(".", ",");
    }

    private String fmtW(double v) {
        return String.format("%,.0f", v).replace(",", ".");
    }

    // ---------- swipe ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchX = e.getX(); touchY = e.getY(); touchT = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - touchX, dy = e.getY() - touchY;
                long dt = System.currentTimeMillis() - touchT;
                if (dt < 700 && Math.abs(dx) > 70 && Math.abs(dy) < 50
                        && Math.abs(dx) > 2 * Math.abs(dy)) {
                    int next = dx < 0 ? Math.min(2, tab + 1) : Math.max(0, tab - 1);
                    if (next != tab) selectTab(next);
                }
                break;
        }
        return super.onTouchEvent(e);
    }

    /** Line chart with miner bands + envelope (same honesty model as web UI). */
    private class ChartView extends View {
        private JSONObject data;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChartView() {
            super(MainActivity.this);
            line.setColor(C_WARN); line.setStrokeWidth(dp(2));
            line.setStyle(Paint.Style.STROKE);
            band.setColor(0x3DE9B44D);
            mb.setColor(0x2946D17F);
            txt.setColor(C_DIM); txt.setTextSize(dp(11));
        }

        void setData(JSONObject d) { data = d; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            if (data == null) { c.drawText("lade…", dp(8), getHeight() / 2f, txt); return; }
            JSONArray ts = data.optJSONArray("grid");
            JSONObject ser = data.optJSONObject("series");
            JSONObject g = ser == null ? null : ser.optJSONObject("grid");
            if (g == null && ser != null) g = ser.optJSONObject("exp"); // ältere Server
            if (ts == null || g == null) {
                c.drawText("keine Daten", dp(8), getHeight() / 2f, txt); return;
            }
            JSONArray v = g.optJSONArray("v"), lo = g.optJSONArray("lo"),
                    hi = g.optJSONArray("hi"), mArr = data.optJSONArray("m");
            int n = Math.min(ts.length(), v == null ? 0 : v.length());
            if (n < 2) { c.drawText("zu wenig Daten", dp(8), getHeight()/2f, txt); return; }
            float W = getWidth(), H = getHeight(), pad = dp(6);
            float max = 1, min = 0;
            for (int i = 0; i < n; i++) {
                if (!hi.isNull(i) && (float) hi.optDouble(i, 0) > max)
                    max = (float) hi.optDouble(i, 0);
                if (!lo.isNull(i) && (float) lo.optDouble(i, 0) < min)
                    min = (float) lo.optDouble(i, 0);
            }
            if (max == min) max = min + 1;

            if (mArr != null) for (int i = 0; i < Math.min(n, mArr.length()); i++) {
                if (mArr.optInt(i) == 1) {
                    int j = i;
                    while (j < n - 1 && mArr.optInt(j + 1) == 1) j++;
                    c.drawRect(xOf(i, n, W, pad), pad, xOf(j, n, W, pad), H - dp(20), mb);
                    i = j;
                }
            }
            Path env = new Path();
            boolean started = false;
            for (int i = 0; i < n; i++) {
                if (hi.isNull(i)) continue;
                float x = xOf(i, n, W, pad), y = yOf((float) hi.optDouble(i, 0), min, max, H);
                if (!started) { env.moveTo(x, y); started = true; } else env.lineTo(x, y);
            }
            for (int i = n - 1; i >= 0; i--) {
                if (lo.isNull(i) || hi.isNull(i)) continue;
                env.lineTo(xOf(i, n, W, pad), yOf((float) lo.optDouble(i, 0), min, max, H));
            }
            env.close();
            c.drawPath(env, band);
            Path ln = new Path();
            boolean pen = false;
            for (int i = 0; i < n; i++) {
                if (v.isNull(i)) { pen = false; continue; }
                float x = xOf(i, n, W, pad), y = yOf((float) v.optDouble(i, 0), min, max, H);
                if (!pen) { ln.moveTo(x, y); pen = true; } else ln.lineTo(x, y);
            }
            c.drawPath(ln, line);
        }

        private float xOf(int i, int n, float W, float pad) {
            return pad + i / (float) (n - 1) * (W - 2 * pad);
        }

        private float yOf(float val, float min, float max, float H) {
            return H - dp(20) - (val - min) / (max - min) * (H - dp(30));
        }
    }
}
