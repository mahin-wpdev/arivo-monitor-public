import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ArivoMonitorApp());

class ArivoMonitorApp extends StatefulWidget {
  const ArivoMonitorApp({super.key});
  @override
  State<ArivoMonitorApp> createState() => _ArivoMonitorAppState();
}

class _ArivoMonitorAppState extends State<ArivoMonitorApp> {
  static const _control = MethodChannel('com.arivo.monitor/control');

  @override
  void initState() {
    super.initState();
    Future.microtask(() => _control.invokeMethod('startMonitoring'));
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Arivo',
        home: const Scaffold(
          body: Center(
            child: Text('Arivo is Active',
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.w600)),
          ),
        ),
      );
}
