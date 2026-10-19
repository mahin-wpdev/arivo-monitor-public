import 'package:arivo_monitor/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows Arivo active status', (WidgetTester tester) async {
    await tester.pumpWidget(const ArivoMonitorApp());
    expect(find.text('Arivo is Active'), findsOneWidget);
  });
}
