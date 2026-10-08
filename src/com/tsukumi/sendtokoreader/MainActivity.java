package com.tsukumi.sendtokoreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: where "Send to KOReader" uploads to. */
public class MainActivity extends Activity {
    static final String EXTRA_NEED_SETUP = "need_setup";

    private EditText folder;
    private EditText user;
    private EditText password;
    private TextView status;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences(WebDav.PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView head = new TextView(this);
        head.setText("Send to KOReader");
        head.setTextSize(24);
        root.addView(head);

        TextView intro = new TextView(this);
        intro.setPadding(0, dp(8), 0, dp(16));
        intro.setText("在任意 App 里分享电子书（或“用其他应用打开”），选择“发送到 KOReader”，"
                + "书会直接上传到下面的 WebDAV 文件夹。KOReader 的 Remote Library 指向同一个文件夹即可。");
        root.addView(intro);

        folder = field(root, "WebDAV 文件夹地址", p.getString("folder", ""),
                "https://example.com/dav/books", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = field(root, "用户名", p.getString("user", ""),
                null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = field(root, "密码", p.getString("password", ""),
                null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(16), 0, 0);
        Button save = new Button(this);
        save.setText("保存并测试");
        save.setOnClickListener(v -> saveAndTest());
        row.addView(save);
        root.addView(row);

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        if (getIntent().getBooleanExtra(EXTRA_NEED_SETUP, false)) {
            status.setText("请先填写并保存云端设置，然后重新分享。");
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String value, String hint, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(8), 0, 0);
        root.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        e.setHint(hint);
        root.addView(e);
        return e;
    }

    private void saveAndTest() {
        String f = folder.getText().toString().trim();
        if (!f.startsWith("http://") && !f.startsWith("https://")) {
            status.setText("地址需要以 https:// 或 http:// 开头");
            return;
        }
        getSharedPreferences(WebDav.PREFS, Context.MODE_PRIVATE).edit()
                .putString("folder", f)
                .putString("user", user.getText().toString().trim())
                .putString("password", password.getText().toString())
                .apply();
        status.setText("已保存，正在测试连接…");
        WebDav dav = WebDav.load(this);
        new Thread(() -> {
            String msg;
            boolean missing = false;
            try {
                int code = dav.checkFolder();
                if (code / 100 == 2) msg = "✓ 连接正常，可以开始分享了";
                else if (code == 401) msg = "✗ 用户名或密码错误 (401)";
                else if (code == 403) msg = "✗ 没有写入权限 (403)";
                else if (code == 404 || code == 409) { msg = "文件夹不存在"; missing = true; }
                else msg = "✗ 服务器返回 HTTP " + code;
            } catch (Exception e) {
                msg = "✗ 连接失败：" + e.getMessage();
            }
            final String m = msg;
            final boolean ask = missing;
            runOnUiThread(() -> {
                status.setText(m);
                if (ask) offerCreate(dav);
            });
        }).start();
    }

    private void offerCreate(WebDav dav) {
        new AlertDialog.Builder(this)
                .setMessage("云端还没有这个文件夹，要创建吗？")
                .setPositiveButton("创建", (d, w) -> new Thread(() -> {
                    String m;
                    try {
                        int code = dav.createFolder();
                        m = code / 100 == 2 ? "✓ 已创建，可以开始分享了" : "✗ 创建失败 HTTP " + code;
                    } catch (Exception e) {
                        m = "✗ 创建失败：" + e.getMessage();
                    }
                    final String fm = m;
                    runOnUiThread(() -> status.setText(fm));
                }).start())
                .setNegativeButton("取消", null)
                .show();
        Toast.makeText(this, "文件夹不存在", Toast.LENGTH_SHORT).show();
    }
}
