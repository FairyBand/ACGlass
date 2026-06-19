package com.acglass.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private EditText socketEdit;
    private EditText droidspacesEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("Wayland display socket");
        label.setTextSize(14);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(24);
        root.addView(label, labelLp);

        socketEdit = new EditText(this);
        socketEdit.setSingleLine(true);
        socketEdit.setText(ACGlassPrefs.getSocketPath(this));
        root.addView(socketEdit, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView droidspacesLabel = new TextView(this);
        droidspacesLabel.setText("Droidspaces CLI path");
        droidspacesLabel.setTextSize(14);
        LinearLayout.LayoutParams droidspacesLabelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        droidspacesLabelLp.topMargin = dp(16);
        root.addView(droidspacesLabel, droidspacesLabelLp);

        droidspacesEdit = new EditText(this);
        droidspacesEdit.setSingleLine(true);
        droidspacesEdit.setText(ACGlassPrefs.getDroidspacesPath(this));
        root.addView(droidspacesEdit, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(v -> {
            ACGlassPrefs.setSocketPath(this, socketEdit.getText().toString());
            ACGlassPrefs.setDroidspacesPath(this, droidspacesEdit.getText().toString());
            socketEdit.setText(ACGlassPrefs.getSocketPath(this));
            droidspacesEdit.setText(ACGlassPrefs.getDroidspacesPath(this));
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.topMargin = dp(12);
        root.addView(saveButton, buttonLp);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
