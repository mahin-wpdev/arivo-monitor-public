import 'package:arivo_monitor/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows Arivo update status and current version', (WidgetTester tester) async {
    await tester.pumpWidget(const ArivoMonitorApp());
    expect(find.text('Arivo'), findsOneWidget);
    expect(find.text('App update'), findsOneWidget);
    expect(find.text('Check for updates'), findsOneWidget);
  });
}
