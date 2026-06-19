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
    private EditText androidSocketEdit;
    private EditText containerSocketEdit;
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

        TextView androidSocketLabel = new TextView(this);
        androidSocketLabel.setText("Android display socket");
        androidSocketLabel.setTextSize(14);
        LinearLayout.LayoutParams androidSocketLabelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        androidSocketLabelLp.topMargin = dp(24);
        root.addView(androidSocketLabel, androidSocketLabelLp);

        androidSocketEdit = new EditText(this);
        androidSocketEdit.setSingleLine(true);
        androidSocketEdit.setText(ACGlassPrefs.getAndroidSocketPath(this));
        root.addView(androidSocketEdit, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView containerSocketLabel = new TextView(this);
        containerSocketLabel.setText("Container display socket");
        containerSocketLabel.setTextSize(14);
        LinearLayout.LayoutParams containerSocketLabelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        containerSocketLabelLp.topMargin = dp(16);
        root.addView(containerSocketLabel, containerSocketLabelLp);

        containerSocketEdit = new EditText(this);
        containerSocketEdit.setSingleLine(true);
        containerSocketEdit.setText(ACGlassPrefs.getContainerSocketPath(this));
        root.addView(containerSocketEdit, new LinearLayout.LayoutParams(
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
            ACGlassPrefs.setAndroidSocketPath(this,
                androidSocketEdit.getText().toString());
            ACGlassPrefs.setContainerSocketPath(this,
                containerSocketEdit.getText().toString());
            ACGlassPrefs.setDroidspacesPath(this, droidspacesEdit.getText().toString());
            androidSocketEdit.setText(ACGlassPrefs.getAndroidSocketPath(this));
            containerSocketEdit.setText(ACGlassPrefs.getContainerSocketPath(this));
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
