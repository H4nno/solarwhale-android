package de.solarwhale.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SolarWhale Android app — native dashboard, no WebView.
 * Polls the SolarWhale web API (same host the user configures on first start)
 * and renders the same glassy dark design with Canvas.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "solarwhale";
    private String baseUrl;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private DashboardView dash;
    private volatile JSONObject lastStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("url", "http://192.168.178.112:8080");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF04060C);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("SolarWhale");
        title.setTextColor(0xFFE9EFFA);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("PV energy arbitrage & Bitcoin mining");
        sub.setTextColor(0xFF8F9CB5);
        sub.setTextSize(12);
        root.addView(sub);
        root.addView(makeUrlBar(), ViewGroup.LayoutParams.MATCH_PARENT, dp(48));

        ScrollView sc = new ScrollView(this);
        dash = new DashboardView(this);
        sc.addView(dash);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        pollLoop();
    }

    private View makeUrlBar() {
        final TextView url = new TextView(this);
        url.setText(baseUrl);
        url.setTextColor(0xFF6DB3FF);
        url.setTextSize(13);
        url.setPadding(0, dp(8), 0, dp(8));
        url.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("SolarWhale URL");
            final android.widget.EditText in = new android.widget.EditText(this);
            in.setText(baseUrl);
            in.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            b.setView(in);
            b.setPositiveButton("Save", (d, w) -> {
                String u = in.getText().toString().trim();
                if (!u.startsWith("http")) u = "http://" + u;
                if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                baseUrl = u;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("url", baseUrl).apply();
                url.setText(baseUrl);
                pollLoop();
            });
            b.setNegativeButton("Cancel", null);
            b.show();
        });
        return url;
    }

    private void pollLoop() {
        pool.execute(() -> {
            try {
                String body = httpGet(baseUrl + "/api/status");
                lastStatus = new JSONObject(body);
            } catch (Exception e) {
                lastStatus = null;
            }
            ui.post(() -> { if (dash != null) dash.invalidate(); });
            ui.postDelayed(this::pollLoop, 12000);
        });
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Canvas dashboard: same layout/colors as the web UI. */
    private class DashboardView extends View {
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCard = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBarBg = new Paint(Paint.ANTI_ALIAS_FLAG);

        DashboardView(android.content.Context ctx) {
            super(ctx);
            pText.setColor(0xFFE9EFFA);
            pDim.setColor(0xFF8F9CB5);
            pCard.setColor(0x9E101626);
            pBorder.setColor(0x2A94B2FF);
            pBarBg.setColor(0x1E94B2FF);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF04060C);
            int w = getWidth();
            int x = dp(4), cw = (w - dp(24)) / 2, ch = dp(96);
            int y = dp(8);
            JSONObject m = lastStatus == null ? null
                    : lastStatus.optJSONObject("metrics");

            y = card(c, x, y, cw, ch, "BATTERY (SOC)",
                    m == null ? "–" : num(m.optDouble("soc")) + " %",
                    m == null ? "" : battNote(m));
            y = card(c, x + cw + dp(12), y - ch, cw, ch, "PV GENERATION",
                    m == null ? "–" : num(m.optDouble("pv_w")) + " W", "");
            y += dp(12);
            y = card(c, x, y, cw, ch, "GRID",
                    m == null ? "–" : num(m.optDouble("grid_power_w")) + " W", "");
            y = card(c, x + cw + dp(12), y - ch, cw, ch, "HOUSE LOAD",
                    m == null ? "–" : num(m.optDouble("house_load_w")) + " W", "");
            y += dp(16);

            boolean minerOn = lastStatus != null && (
                    lastStatus.optBoolean("miner_on")
                    || "on".equals(lastStatus.optString("manual")));
            y = card(c, x, y, w - dp(24), dp(76), "BITCOIN MINER",
                    minerOn ? "RUNNING" : "STOPPED",
                    lastStatus == null ? "not connected" : "manual: "
                            + lastStatus.optString("manual", "auto"));
            y += dp(16);

            // weather strip (up to 5 days)
            JSONObject wx = lastStatus == null ? null
                    : lastStatus.optJSONObject("weather");
            y = card(c, x, y, w - dp(24), dp(88), "SUN & PV FORECAST",
                    wx == null ? "loading…" : wxDays(wx), "");
            y += dp(16);

            JSONObject st = lastStatus == null ? null
                    : lastStatus.optJSONObject("stats");
            JSONObject pr = lastStatus == null ? null
                    : lastStatus.optJSONObject("prices");
            double kwh = st == null ? 0 : st.optDouble("on_s", 0) / 3600.0
                    * (lastStatus.optDouble("miner_w", 2200)) / 1000.0;
            double eur = kwh * (pr == null ? 0.07 : pr.optDouble("mining_value", 0.07));
            String cur = pr == null ? "€" : pr.optString("currency", "€");
            y = card(c, x, y, w - dp(24), dp(88), "YIELD TODAY",
                    String.format("%.2f %s", eur, cur),
                    String.format("%.0f W × %.1f h = %.1f kWh mined",
                            lastStatus == null ? 0 : lastStatus.optDouble("miner_w", 2200),
                            st == null ? 0 : st.optDouble("on_s", 0) / 3600.0, kwh));
        }

        private String battNote(JSONObject m) {
            double bw = m.optDouble("batt_w", 0);
            String s = bw >= 0 ? "charging " : "discharging ";
            double a = Math.abs(bw);
            s += a >= 1000 ? String.format("%.1f kW", a / 1000) : String.format("%.0f W", a);
            if (m.has("batt_temp_c") && !m.isNull("batt_temp_c"))
                s += String.format(" · %.0f °C", m.optDouble("batt_temp_c"));
            return s;
        }

        private String wxDays(JSONObject wx) {
            JSONArray days = wx.optJSONArray("days");
            if (days == null || days.length() == 0) return "no data";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, days.length()); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(d.optString("date", "").substring(5))
                  .append(" ☀").append(num(d.optDouble("sun_h"))).append("h");
            }
            return sb.toString();
        }

        private String num(double v) {
            if (Double.isNaN(v)) return "0";
            if (Math.abs(v) >= 1000) return String.format("%,.0f", v).replace(",", ".");
            if (v == Math.floor(v)) return String.format("%d", (long) v);
            return String.format("%.1f", v).replace(".", ",");
        }

        /** One glassy card; returns the next y (bottom). */
        private int card(Canvas c, int x, int y, int w, int h,
                         String label, String value, String note) {
            c.drawRoundRect(x, y, x + w, y + h, dp(14), dp(14), pCard);
            c.drawRoundRect(x, y, x + w, y + h, dp(14), dp(14), pBorder);
            pText.setTextSize(dp(11));
            c.drawText(label.toUpperCase(), x + dp(12), y + dp(20), pDim);
            pText.setTextSize(dp(26));
            pText.setFakeBoldText(true);
            c.drawText(value, x + dp(12), y + dp(52), pText);
            pText.setFakeBoldText(false);
            if (note != null && !note.isEmpty()) {
                pDim.setTextSize(dp(11));
                c.drawText(note, x + dp(12), y + h - dp(10), pDim);
            }
            return y + h;
        }
    }
}
