package dev.silphmod.nativecheck;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import dev.rootcatch.nativeengine.NativeSessionState;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AccountRotationCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean ROTATING = new AtomicBoolean(false);
    public static volatile boolean manualStopRequested = false;

    private AccountRotationCoordinator() {}

    public static void addRotationCard(MainActivity activity, ModeUi ui, Runnable refreshUi) {
        PtcAccountVault vault = new PtcAccountVault(activity);
        List<PtcAccountVault.Account> accounts = vault.list();
        PtcAccountVault.Account current = vault.activeAccount();

        LinearLayout card = ui.card("PTC ACCOUNTS & AUTO-ROTATION");
        String summary = accounts.isEmpty()
            ? "No PTC accounts imported yet"
            : accounts.size() + " PTC account(s) · Active: " + (current != null ? current.displayName : "None");
        TextView info = ui.text(summary, 14, ModeUi.TEXT);
        card.addView(info);

        ui.button(card, "Import .eevxbackup JSON or refresh tokens", false).setOnClickListener(v ->
            showImportDialog(activity, vault, refreshUi)
        );
        ui.space(card, 8);

        ui.button(card, "Sign in single PTC account (Pokémon.com)", false).setOnClickListener(v ->
            showOAuthDialog(activity, vault, refreshUi)
        );
        ui.space(card, 8);

        ui.button(card, "Switch next PTC account & launch PoGo", false).setOnClickListener(v -> {
            if (CheckService.view.running) {
                Toast.makeText(activity, "Stop AutoRaid before manual switch", Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                try {
                    PtcAccountVault.Account next = vault.advanceNextAccount();
                    if (next == null) throw new IllegalStateException("No valid PTC accounts in vault");
                    PtcAccountVault.Account fresh = vault.refreshAccount(next);
                    PogoPtcSwitcher.switchAndLaunch(fresh);
                    MAIN.post(() -> {
                        Toast.makeText(activity, "Switched to " + fresh.displayName, Toast.LENGTH_LONG).show();
                        refreshUi.run();
                    });
                } catch (Exception err) {
                    MAIN.post(() -> Toast.makeText(activity, "Switch failed: " + err.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "silph-manual-switch").start();
        });
        ui.space(card, 8);

        Switch autoRotate = ui.toggle(card, "Auto-rotate PTC on AutoRaid stop", vault.isAutoRotateEnabled());
        autoRotate.setOnCheckedChangeListener((btn, checked) -> vault.setAutoRotateEnabled(checked));

        String hook = vault.getWebhookUrl();
        ui.button(
            card,
            hook.isEmpty() ? "Set Discord Webhook URL (optional)" : "Discord Webhook: configured ✓",
            false
        ).setOnClickListener(v -> showWebhookDialog(activity, vault, refreshUi));
    }

    public static void onCheckServiceFinished(
        Context context,
        int mode,
        NativeCheckRunner.Result result,
        NativeSettings settings
    ) {
        boolean wasManual = manualStopRequested;
        manualStopRequested = false;

        PtcAccountVault vault = new PtcAccountVault(context);
        PtcAccountVault.Account current = vault.activeAccount();
        String acctLabel = current != null && !current.displayName.isEmpty() ? current.displayName : "Current Account";

        NativeSessionState.Totals t = settings.totals(mode);
        long sec = settings.elapsed(mode) / 1000L;
        String elapsedStr = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);

        String hook = vault.getWebhookUrl();
        if (!hook.isEmpty()) {
            String desc = "**Account:** " + acctLabel
                + "\n**Reason:** " + (result != null ? result.reason : "stopped")
                + "\n**Raids / Spins:** " + t.spun + " · **Catches:** " + t.caught + " · **Shinies:** " + t.shinies
                + "\n**Elapsed:** " + elapsedStr;
            DiscordWebhookClient.postAsync(hook, "SilphMod Session Stopped", desc, 0x61E9B6);
        }

        if (wasManual || mode != 6 || !vault.isAutoRotateEnabled() || !ROTATING.compareAndSet(false, true)) {
            return;
        }

        new Thread(() -> {
            try {
                PtcAccountVault.Account next = vault.advanceNextAccount();
                if (next == null) {
                    DiscordWebhookClient.postAsync(hook, "Auto-Rotation Halted", "No valid PTC accounts remaining.", 0xF28B82);
                    return;
                }
                PtcAccountVault.Account fresh = vault.refreshAccount(next);
                PogoPtcSwitcher.switchAndLaunch(fresh);

                int waitSec = vault.getLaunchWaitSeconds();
                DiscordWebhookClient.postAsync(
                    hook,
                    "Rotated PTC Account",
                    "Switched to **" + fresh.displayName + "**. Starting AutoRaid in " + waitSec + "s.",
                    0x91D9A5
                );

                Thread.sleep(waitSec * 1000L);
                try {
                    Intent restart = new Intent(context, CheckService.class)
                        .setAction(CheckService.START)
                        .putExtra("mode", 6);
                    context.startForegroundService(restart);
                } catch (Exception bgRestricted) {
                    try (dev.rootcatch.root.RootShell root = new dev.rootcatch.root.RootShell()) {
                        root.execute(
                            "am start-foreground-service -n "
                                + context.getPackageName()
                                + "/dev.silphmod.nativecheck.CheckService -a "
                                + CheckService.START
                                + " --ei mode 6",
                            8_000
                        );
                    }
                }
            } catch (Exception err) {
                DiscordWebhookClient.postAsync(hook, "Auto-Rotation Error", String.valueOf(err.getMessage()), 0xF28B82);
            } finally {
                ROTATING.set(false);
            }
        }, "silph-auto-rotate").start();
    }

    private static void showImportDialog(MainActivity activity, PtcAccountVault vault, Runnable refreshUi) {
        EditText input = new EditText(activity);
        input.setHint("Paste .eevxbackup JSON or username,refresh_token lines");
        input.setMinLines(4);
        input.setMaxLines(8);
        new AlertDialog.Builder(activity)
            .setTitle("Import PTC Accounts")
            .setView(input)
            .setPositiveButton("Import", (d, w) -> {
                try {
                    int count = vault.importText(input.getText().toString());
                    Toast.makeText(activity, "Imported " + count + " account(s)", Toast.LENGTH_LONG).show();
                    refreshUi.run();
                } catch (Exception err) {
                    Toast.makeText(activity, "Import error: " + err.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void showOAuthDialog(MainActivity activity, PtcAccountVault vault, Runnable refreshUi) {
        try {
            PtcAccountVault.PkceSession pkce = PtcAccountVault.createPkceSession();
            EditText input = new EditText(activity);
            input.setHint("Paste pokemongolive.com/dl?... or ?code=...");
            new AlertDialog.Builder(activity)
                .setTitle("Pokémon.com PTC Sign-In")
                .setMessage("1. Tap Open Login\n2. Sign in and copy the final redirect URL\n3. Paste it below and tap Save")
                .setView(input)
                .setNeutralButton("Open Login", (d, w) -> {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pkce.authUrl)));
                    showCodeOnlyDialog(activity, vault, pkce, refreshUi);
                })
                .setPositiveButton("Save Code", (d, w) -> completeOAuth(activity, vault, input.getText().toString(), pkce.verifier, refreshUi))
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception err) {
            Toast.makeText(activity, err.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void showCodeOnlyDialog(MainActivity activity, PtcAccountVault vault, PtcAccountVault.PkceSession pkce, Runnable refreshUi) {
        EditText input = new EditText(activity);
        input.setHint("Paste redirect URL or code");
        new AlertDialog.Builder(activity)
            .setTitle("Finish PTC Sign-In")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> completeOAuth(activity, vault, input.getText().toString(), pkce.verifier, refreshUi))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void completeOAuth(MainActivity activity, PtcAccountVault vault, String raw, String verifier, Runnable refreshUi) {
        new Thread(() -> {
            try {
                PtcAccountVault.Account acc = vault.exchangeAndSaveCode(raw, verifier);
                MAIN.post(() -> {
                    Toast.makeText(activity, "Saved PTC account: " + acc.displayName, Toast.LENGTH_LONG).show();
                    refreshUi.run();
                });
            } catch (Exception err) {
                MAIN.post(() -> Toast.makeText(activity, "PTC login failed: " + err.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "silph-ptc-oauth").start();
    }

    private static void showWebhookDialog(MainActivity activity, PtcAccountVault vault, Runnable refreshUi) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(vault.getWebhookUrl());
        input.setHint("https://discord.com/api/webhooks/...");
        new AlertDialog.Builder(activity)
            .setTitle("Discord Webhook URL")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                vault.setWebhookUrl(input.getText().toString());
                refreshUi.run();
            })
            .setNeutralButton("Test", (d, w) -> {
                String url = input.getText().toString().trim();
                vault.setWebhookUrl(url);
                DiscordWebhookClient.postAsync(url, "SilphMod Connected", "AutoRaid & PTC rotation notifications active.", 0x91D9A5);
                refreshUi.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
