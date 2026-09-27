package com.lumomusic.mini;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private EditText promptInput;
    private EditText lyricsInput;
    private Spinner languageSpinner;
    private Spinner durationSpinner;
    private TextView statusText;
    private Button playButton;
    private Button saveButton;
    private File lastGenerated;
    private MediaPlayer mediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8, 10, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);

        TextView title = text("LUMO MUSIC MINI", 28, true);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView version = text("v1.9.0 TEST • offline APK", 13, false);
        version.setTextColor(Color.rgb(130, 185, 255));
        root.addView(version, lp(-1, dp(34)));

        TextView note = text(
                "Тестовая сборка: проверка установки, интерфейса, саха-текста и локальной генерации WAV. " +
                "Полные нейросетевые веса v1.9 в этот test APK не включены.",
                13, false
        );
        note.setTextColor(Color.LTGRAY);
        root.addView(note, lp(-1, dp(76)));

        root.addView(label("Язык"));
        languageSpinner = spinner(new String[]{"Авто", "Русский", "Саха тыла"});
        root.addView(languageSpinner, lp(-1, dp(52)));

        root.addView(label("Описание песни"));
        promptInput = input("Например: үөрүү, күн, алаас — светлая современная песня", 3);
        root.addView(promptInput, lp(-1, dp(104)));

        root.addView(label("Текст песни"));
        lyricsInput = input("Можно писать по-саха: ҕ ҥ ө һ ү • ыа иэ уо үө", 5);
        root.addView(lyricsInput, lp(-1, dp(142)));

        root.addView(label("Длительность"));
        durationSpinner = spinner(new String[]{"10 секунд", "15 секунд", "30 секунд"});
        root.addView(durationSpinner, lp(-1, dp(52)));

        Button generateButton = button("✨ Создать тестовый трек");
        root.addView(generateButton, lp(-1, dp(58)));

        playButton = button("▶ Прослушать");
        playButton.setEnabled(false);
        root.addView(playButton, lp(-1, dp(54)));

        saveButton = button("💾 Сохранить WAV");
        saveButton.setEnabled(false);
        root.addView(saveButton, lp(-1, dp(54)));

        statusText = text("Готов к офлайн-генерации.", 14, false);
        statusText.setTextColor(Color.rgb(160, 220, 180));
        statusText.setPadding(0, dp(16), 0, 0);
        root.addView(statusText);

        generateButton.setOnClickListener(v -> generateTrack());
        playButton.setOnClickListener(v -> playLast());
        saveButton.setOnClickListener(v -> saveLast());

        setContentView(scroll);
    }

    private void generateTrack() {
        final String prompt = promptInput.getText().toString().trim();
        final String lyrics = lyricsInput.getText().toString().trim();
        final int duration = new int[]{10, 15, 30}[durationSpinner.getSelectedItemPosition()];
        final String selectedLanguage = String.valueOf(languageSpinner.getSelectedItem());

        playButton.setEnabled(false);
        saveButton.setEnabled(false);
        statusText.setText("Создаю WAV прямо на телефоне…");

        new Thread(() -> {
            try {
                String combined = (prompt + " " + lyrics).toLowerCase(Locale.ROOT);
                boolean sakhaDetected = selectedLanguage.equals("Саха тыла") ||
                        (selectedLanguage.equals("Авто") && containsSakha(combined));

                File out = new File(getCacheDir(), "lumo_test_track.wav");
                synthesizeWav(out, prompt, lyrics, duration, sakhaDetected);
                lastGenerated = out;

                runOnUiThread(() -> {
                    String lang = sakhaDetected ? "Саха режим распознан" : "Обычный режим";
                    statusText.setText("Готово • " + lang + " • " + duration + " сек • без интернета");
                    playButton.setEnabled(true);
                    saveButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Ошибка генерации: " + e.getMessage()));
            }
        }).start();
    }

    private void synthesizeWav(File out, String prompt, String lyrics, int seconds, boolean sakha) throws IOException {
        final int sampleRate = 22050;
        final int totalSamples = sampleRate * seconds;
        short[] pcm = new short[totalSamples];

        long seed = ((long) prompt.hashCode() << 32) ^ lyrics.hashCode();
        Random random = new Random(seed);
        int[] scale = ((seed & 1L) == 0L)
                ? new int[]{0, 2, 4, 7, 9, 12}
                : new int[]{0, 3, 5, 7, 10, 12};

        double bpm = 88 + Math.abs(seed % 48);
        double beatSeconds = 60.0 / bpm;
        double rootHz = sakha ? 196.0 : 220.0;
        int phraseOffset = Math.abs(prompt.hashCode()) % scale.length;

        for (int i = 0; i < totalSamples; i++) {
            double t = i / (double) sampleRate;
            int beat = (int) (t / beatSeconds);
            int step = scale[(beat / 2 + phraseOffset) % scale.length];
            double melodyHz = rootHz * Math.pow(2.0, step / 12.0);
            double bassHz = melodyHz / 2.0;

            double phase = 2.0 * Math.PI * melodyHz * t;
            double bass = Math.sin(2.0 * Math.PI * bassHz * t) * 0.20;
            double lead = (Math.sin(phase) + 0.32 * Math.sin(phase * 2.0)) * 0.28;

            double beatPhase = (t % beatSeconds) / beatSeconds;
            double kick = Math.sin(2.0 * Math.PI * (70.0 - 28.0 * beatPhase) * t)
                    * Math.exp(-beatPhase * 10.0) * 0.34;
            double hatGate = ((beat * 2 + (int) (t / (beatSeconds / 2.0))) % 2 == 0) ? 1.0 : 0.5;
            double hat = (random.nextDouble() * 2.0 - 1.0)
                    * Math.exp(-((t % (beatSeconds / 2.0)) / (beatSeconds / 2.0)) * 22.0)
                    * 0.055 * hatGate;

            double vocalTexture = 0.0;
            if (!lyrics.isEmpty()) {
                double syllableRate = sakha ? 3.1 : 3.6;
                double local = (t * syllableRate) % 1.0;
                double env = Math.sin(Math.PI * Math.min(1.0, local));
                double formant = sakha ? 2.35 : 2.7;
                vocalTexture = Math.sin(phase * formant + 0.018 * Math.sin(t * 31.0))
                        * env * 0.10;
            }

            double intro = Math.min(1.0, t / 0.8);
            double outro = Math.min(1.0, (seconds - t) / 0.8);
            double envelope = Math.max(0.0, Math.min(intro, outro));

            double sample = (lead + bass + kick + hat + vocalTexture) * envelope;
            sample = Math.tanh(sample * 1.35);
            pcm[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (int) (sample * 26000.0)));
        }

        writeWav(out, pcm, sampleRate);
    }

    private void playLast() {
        if (lastGenerated == null || !lastGenerated.exists()) return;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(lastGenerated.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            statusText.setText("▶ Воспроизведение тестового WAV");
        } catch (Exception e) {
            statusText.setText("Ошибка воспроизведения: " + e.getMessage());
        }
    }

    private void saveLast() {
        if (lastGenerated == null || !lastGenerated.exists()) return;
        File musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (musicDir == null) {
            statusText.setText("Не удалось открыть папку приложения.");
            return;
        }
        File folder = new File(musicDir, "LumoMusicTest");
        if (!folder.exists() && !folder.mkdirs()) {
            statusText.setText("Не удалось создать папку LumoMusicTest.");
            return;
        }
        File target = new File(folder, "Lumo-" + System.currentTimeMillis() + ".wav");
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(lastGenerated);
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            in.close();
            out.close();
            statusText.setText("Сохранено: " + target.getAbsolutePath());
        } catch (Exception e) {
            statusText.setText("Ошибка сохранения: " + e.getMessage());
        }
    }

    private static void writeWav(File file, short[] pcm, int sampleRate) throws IOException {
        int dataSize = pcm.length * 2;
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            writeAscii(out, "RIFF");
            writeLeInt(out, 36 + dataSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLeInt(out, 16);
            writeLeShort(out, (short) 1);
            writeLeShort(out, (short) 1);
            writeLeInt(out, sampleRate);
            writeLeInt(out, sampleRate * 2);
            writeLeShort(out, (short) 2);
            writeLeShort(out, (short) 16);
            writeAscii(out, "data");
            writeLeInt(out, dataSize);
            for (short s : pcm) writeLeShort(out, s);
        }
    }

    private static void writeAscii(DataOutputStream out, String s) throws IOException {
        out.writeBytes(s);
    }

    private static void writeLeInt(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >>> 8) & 0xff);
        out.writeByte((v >>> 16) & 0xff);
        out.writeByte((v >>> 24) & 0xff);
    }

    private static void writeLeShort(DataOutputStream out, short v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >>> 8) & 0xff);
    }

    private boolean containsSakha(String text) {
        return text.indexOf('ҕ') >= 0 || text.indexOf('ҥ') >= 0 ||
                text.indexOf('ө') >= 0 || text.indexOf('һ') >= 0 ||
                text.indexOf('ү') >= 0 || text.contains("ыа") ||
                text.contains("иэ") || text.contains("уо") || text.contains("үө");
    }

    private TextView label(String value) {
        TextView t = text(value, 14, true);
        t.setTextColor(Color.rgb(205, 210, 225));
        t.setPadding(0, dp(13), 0, dp(6));
        return t;
    }

    private EditText input(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(115, 120, 135));
        e.setTextColor(Color.WHITE);
        e.setBackgroundColor(Color.rgb(25, 29, 40));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        return e;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, values);
        s.setAdapter(a);
        return s;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
