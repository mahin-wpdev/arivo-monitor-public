import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ArivoMonitorApp());

class ArivoMonitorApp extends StatelessWidget {
  const ArivoMonitorApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Arivo',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF16845B)),
      useMaterial3: true,
    ),
    home: const MonitorHomePage(),
  );
}

class MonitorHomePage extends StatefulWidget {
  const MonitorHomePage({super.key});

  @override
  State<MonitorHomePage> createState() => _MonitorHomePageState();
}

class _MonitorHomePageState extends State<MonitorHomePage> {
  static const _control = MethodChannel('com.arivo.monitor/control');
  Map<String, dynamic> _update = const {};
  Timer? _statusPoll;
  bool _working = false;

  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      await _control.invokeMethod<void>('startMonitoring');
      await _readUpdateStatus();
    });
    _statusPoll = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _readUpdateStatus(),
    );
  }

  @override
  void dispose() {
    _statusPoll?.cancel();
    super.dispose();
  }

  Future<void> _readUpdateStatus() async {
    try {
      final status = await _control.invokeMapMethod<String, dynamic>(
        'getUpdateStatus',
      );
      if (mounted && status != null) setState(() => _update = status);
    } on PlatformException {
      // The native update checker may not be ready during the first frame.
    }
  }

  Future<void> _checkForUpdates() async {
    setState(() => _working = true);
    try {
      await _control.invokeMethod<void>('checkForUpdates');
    } on PlatformException {
      // Keep the last known status visible; the next poll refreshes it.
    } finally {
      if (mounted) setState(() => _working = false);
      await _readUpdateStatus();
    }
  }

  Future<void> _installUpdate() async {
    await _control.invokeMethod<void>('installUpdate');
    await _readUpdateStatus();
  }

  String get _status => (_update['status'] as String?) ?? 'unknown';

  String get _statusTitle => switch (_status) {
    'checking' => 'Checking for updates',
    'downloading' => 'Downloading update',
    'downloaded' => 'Update downloaded',
    'waiting_permission' => 'Installation permission needed',
    'installing' => 'Waiting for Android installer',
    'failed' => 'Could not download update',
    'current' => 'App is up to date',
    _ => 'Update status is not available',
  };

  String get _statusMessage {
    final latestVersion = (_update['latest_version'] as String?) ?? '';
    final latestBuild = (_update['latest_build'] as int?) ?? 0;
    final progress = (_update['progress'] as int?) ?? 0;
    final error = (_update['error'] as String?) ?? '';
    switch (_status) {
      case 'checking':
        return 'Contacting the update server…';
      case 'downloading':
        return latestVersion.isEmpty
            ? 'Downloading the latest version ($progress%).'
            : 'Version $latestVersion+$latestBuild · $progress% downloaded';
      case 'downloaded':
        return 'Version $latestVersion+$latestBuild is ready to install.';
      case 'waiting_permission':
        return 'Allow Arivo to install updates in Android settings, then return here.';
      case 'installing':
        return 'Complete the update in the Android installer.';
      case 'failed':
        return error.isEmpty
            ? 'Check your internet connection and try again.'
            : error;
      case 'current':
        return 'You have the latest installed version.';
      default:
        return 'Tap Check for updates to see whether a new version is available.';
    }
  }

  IconData get _statusIcon => switch (_status) {
    'downloading' || 'checking' => Icons.downloading_rounded,
    'downloaded' => Icons.system_update_alt_rounded,
    'failed' => Icons.cloud_off_rounded,
    'current' => Icons.check_circle_outline_rounded,
    'waiting_permission' => Icons.security_rounded,
    'installing' => Icons.open_in_new_rounded,
    _ => Icons.system_update_rounded,
  };

  Color _statusColor(ColorScheme colors) => switch (_status) {
    'failed' => colors.error,
    'current' || 'downloaded' => const Color(0xFF16845B),
    _ => colors.primary,
  };

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final progress = ((_update['progress'] as int?) ?? 0).clamp(0, 100);
    final requiredUpdate = (_update['required'] as bool?) ?? false;
    final busy = _working || _status == 'checking' || _status == 'downloading';
    final canInstall =
        _status == 'downloaded' || _status == 'waiting_permission';
    final color = _statusColor(colors);

    return Scaffold(
      backgroundColor: const Color(0xFFF5F7F6),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(
                    Icons.shield_moon_rounded,
                    size: 54,
                    color: Color(0xFF16845B),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Arivo',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineMedium
                        ?.copyWith(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Monitoring is active',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyLarge
                        ?.copyWith(color: colors.onSurfaceVariant),
                  ),
                  const SizedBox(height: 28),
                  Card(
                    elevation: 0,
                    color: colors.surface,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(20),
                      side: BorderSide(color: colors.outlineVariant),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Row(
                            children: [
                              Icon(
                                Icons.system_update_alt_rounded,
                                color: colors.primary,
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Text(
                                  'App update',
                                  style: Theme.of(context).textTheme.titleLarge
                                      ?.copyWith(fontWeight: FontWeight.w700),
                                ),
                              ),
                              if (requiredUpdate)
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 10,
                                    vertical: 5,
                                  ),
                                  decoration: BoxDecoration(
                                    color: colors.errorContainer,
                                    borderRadius: BorderRadius.circular(30),
                                  ),
                                  child: Text(
                                    'Required',
                                    style: TextStyle(
                                      color: colors.onErrorContainer,
                                      fontWeight: FontWeight.w600,
                                      fontSize: 12,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                          const SizedBox(height: 20),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(_statusIcon, color: color, size: 26),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      _statusTitle,
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium
                                          ?.copyWith(
                                            fontWeight: FontWeight.w600,
                                          ),
                                    ),
                                    const SizedBox(height: 5),
                                    Text(
                                      _statusMessage,
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodyMedium
                                          ?.copyWith(
                                            color: colors.onSurfaceVariant,
                                          ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                          if (_status == 'downloading' ||
                              _status == 'downloaded') ...[
                            const SizedBox(height: 18),
                            LinearProgressIndicator(
                              value: progress / 100,
                              minHeight: 7,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            const SizedBox(height: 7),
                            Text(
                              '$progress%',
                              textAlign: TextAlign.end,
                              style: Theme.of(context).textTheme.labelMedium,
                            ),
                          ],
                          const SizedBox(height: 18),
                          FilledButton.icon(
                            onPressed: busy
                                ? null
                                : canInstall
                                ? _installUpdate
                                : _checkForUpdates,
                            icon: Icon(
                              canInstall
                                  ? Icons.install_mobile_rounded
                                  : Icons.refresh_rounded,
                            ),
                            label: Text(
                              canInstall
                                  ? 'Install update'
                                  : _status == 'failed'
                                  ? 'Retry download'
                                  : 'Check for updates',
                            ),
                          ),
                          const SizedBox(height: 10),
                          Text(
                            'Installed: ${_update['installed_version'] ?? '—'} (${_update['installed_build'] ?? '—'})',
                            textAlign: TextAlign.center,
                            style: Theme.of(context).textTheme.bodySmall
                                ?.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
