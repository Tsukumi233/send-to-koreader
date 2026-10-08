package com.tsukumi.sendtokoreader;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Share target: uploads the shared files straight to the configured folder. */
public class SendActivity extends Activity {
    private TextView title;
    private TextView detail;
    private ProgressBar bar;
    private LinearLayout buttons;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    private static final class Item {
        Uri uri;
        String name;
        long size = -1;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();

        WebDav dav = WebDav.load(this);
        if (dav == null) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_NEED_SETUP, true));
            finish();
            return;
        }
        List<Item> items = collect(getIntent());
        if (items.isEmpty()) {
            fail("没有收到文件", null, null);
            return;
        }
        start(dav, items);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(12));
        root.setMinimumWidth(dp(300));

        title = new TextView(this);
        title.setTextSize(18);
        title.setText("正在发送到 KOReader…");
        root.addView(title);

        detail = new TextView(this);
        detail.setPadding(0, dp(8), 0, dp(8));
        root.addView(detail);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);

        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(8), 0, 0);
        root.addView(buttons);

        addButton("取消", v -> {
            cancelled = true;
            finish();
        });
        setContentView(root);
    }

    private void addButton(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setOnClickListener(l);
        buttons.addView(b);
    }

    private List<Item> collect(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        List<Item> items = new ArrayList<>();
        for (Uri u : uris) {
            Item it = new Item();
            it.uri = u;
            try (Cursor c = getContentResolver().query(u,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) it.name = c.getString(0);
                    if (!c.isNull(1)) it.size = c.getLong(1);
                }
            } catch (Exception ignored) {
                // file:// URIs or providers without metadata
            }
            if (it.name == null || it.name.isEmpty()) it.name = u.getLastPathSegment();
            if (it.name == null || it.name.isEmpty()) it.name = "book-" + System.currentTimeMillis();
            it.name = it.name.replace('/', '_');
            items.add(it);
        }
        return items;
    }

    private void start(WebDav dav, List<Item> items) {
        long total = 0;
        boolean known = true;
        for (Item it : items) {
            if (it.size < 0) known = false;
            else total += it.size;
        }
        final long totalBytes = known ? total : -1;
        bar.setIndeterminate(totalBytes <= 0);

        new Thread(() -> {
            long done = 0;
            int ok = 0;
            for (int i = 0; i < items.size() && !cancelled; i++) {
                Item it = items.get(i);
                final String label = items.size() > 1
                        ? String.format("(%d/%d) %s", i + 1, items.size(), it.name) : it.name;
                ui.post(() -> detail.setText(label));
                final long base = done;
                try (InputStream in = getContentResolver().openInputStream(it.uri)) {
                    if (in == null) throw new Exception("无法读取文件");
                    int code = dav.put(it.name, in, it.size, sent -> {
                        if (totalBytes > 0) {
                            int p = (int) ((base + sent) * 1000 / totalBytes);
                            ui.post(() -> bar.setProgress(p));
                        }
                    });
                    if (code < 200 || code >= 300) {
                        throw new Exception(httpMessage(code));
                    }
                    ok++;
                    if (it.size > 0) done += it.size;
                } catch (Exception e) {
                    final String msg = it.name + "\n" + (e.getMessage() != null ? e.getMessage() : e.toString());
                    final int sentOk = ok;
                    ui.post(() -> fail(sentOk > 0 ? String.format("已发送 %d 本，其余失败", sentOk) : "发送失败",
                            msg, () -> {
                                List<Item> rest = items.subList(sentOk, items.size());
                                resetUi();
                                start(dav, new ArrayList<>(rest));
                            }));
                    return;
                }
            }
            if (cancelled) return;
            final int count = ok;
            ui.post(() -> {
                bar.setIndeterminate(false);
                bar.setProgress(1000);
                title.setText(count > 1 ? String.format("✓ 已发送 %d 本", count) : "✓ 已发送");
                detail.setText("在 KOReader 中刷新云端书库即可看到");
                buttons.removeAllViews();
                ui.postDelayed(this::finish, 1500);
            });
        }).start();
    }

    private static String httpMessage(int code) {
        switch (code) {
            case 401: return "HTTP 401：用户名或密码错误";
            case 403: return "HTTP 403：没有写入权限";
            case 404: case 409: return "HTTP " + code + "：目标文件夹不存在";
            case 507: return "HTTP 507：云端空间不足";
            default: return "HTTP " + code;
        }
    }

    private void resetUi() {
        title.setText("正在发送到 KOReader…");
        bar.setProgress(0);
        buttons.removeAllViews();
        addButton("取消", v -> {
            cancelled = true;
            finish();
        });
    }

    private void fail(String head, String msg, Runnable retry) {
        title.setText(head);
        detail.setText(msg != null ? msg : "");
        bar.setVisibility(View.GONE);
        buttons.removeAllViews();
        addButton("设置", v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        if (retry != null) {
            addButton("重试", v -> {
                bar.setVisibility(View.VISIBLE);
                retry.run();
            });
        }
        addButton("关闭", v -> finish());
    }
}
