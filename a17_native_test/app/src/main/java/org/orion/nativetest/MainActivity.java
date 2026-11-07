package org.orion.nativetest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("ORION ANDROID TEST");
        title.setTextColor(Color.BLACK);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText("\nAgar shu yozuv ko'rinsa, telefon APKni ochdi.\n\nKeyingi qadam: ORIONni shu usulda ishga tushirish.");
        message.setTextColor(Color.DKGRAY);
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);

        layout.addView(title);
        layout.addView(message);
        setContentView(layout);
    }
}
