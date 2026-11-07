import 'package:flutter/foundation.dart';
import 'dart:async';
import 'package:chimera_assist/assist_bridge.dart';
import 'package:chimera_assist/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeBridge extends PreviewAssistBridge {
  FakeBridge([super.scenario = 'ready']);
  final calls = <String>[];
  final args = <Json>[];
  Completer<Object?>? pending;
  @override
  Future<Object?> call(String method, [Json arguments = const {}]) {
    calls.add(method);
    args.add(arguments);
    if (method == 'lookup' && pending != null) return pending!.future;
    return super.call(method, arguments);
  }
}

Future<void> mount(
  WidgetTester tester,
  FakeBridge bridge, {
  bool ro = true,
}) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(AssistApp(bridge: bridge, romanian: ro));
  await tester.pump();
}

Future<void> tap(WidgetTester tester, String text) async {
  final target = find.text(text);
  await tester.scrollUntilVisible(
    target,
    300,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.tap(target);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'Cloud welcome exposes no enterprise directory and reveals URL only on explicit choice',
    (tester) async {
      final bridge = FakeBridge('welcome');
      await mount(tester, bridge);
      expect(find.byType(TextField), findsNothing);
      expect(find.text('Chimera Cloud'), findsOneWidget);
      await tap(tester, 'Enterprise / Self-hosted');
      expect(
        find.widgetWithText(TextField, 'Adresa serverului'),
        findsOneWidget,
      );
      await tester.enterText(
        find.byType(TextField),
        'https://erp.example.test',
      );
      await tap(tester, 'Autentificare');
      final i = bridge.calls.indexOf('login');
      expect(bridge.args[i], {'origin': 'https://erp.example.test'});
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'setup makes missing caller delivery and permissions explicit; test does not depend on caller role',
    (tester) async {
      final bridge = FakeBridge('setup');
      bridge.state['overlay'] = true;
      await mount(tester, bridge);
      expect(find.textContaining('nu a transmis încă'), findsOneWidget);
      final activate = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Activează asistentul'),
      );
      expect(activate.onPressed, isNull);
      await tap(tester, 'Testează cercul');
      expect(bridge.calls, contains('testOverlay'));
      expect(bridge.calls, isNot(contains('lookup')));
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'number test forwards entered international number through native call path',
    (tester) async {
      final bridge = FakeBridge();
      await mount(tester, bridge);
      await tester.scrollUntilVisible(
        find.widgetWithText(TextField, 'Număr de telefon'),
        350,
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Număr de telefon'),
        '+44 20 7946 0123',
      );
      await tap(tester, 'Testează cu acest număr');
      final i = bridge.calls.indexOf('testNumber');
      expect(bridge.args[i], {'phone': '+44 20 7946 0123'});
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'manual lookup and partner actions remain usable on narrow screens',
    (tester) async {
      final bridge = FakeBridge();
      await mount(tester, bridge, ro: false);
      await tap(tester, 'Find in Chimera');
      await tap(tester, 'Furnizor demonstrativ · Supplier');
      await tap(tester, 'Invoices');
      expect(find.text('No records available.'), findsOneWidget);
      final i = bridge.calls.indexOf('rows');
      expect(bridge.args[i], {
        'kind': 'supplier',
        'id': 'synthetic',
        'action': 'invoices',
        'offset': 0,
      });
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'session loss clears in-flight data before late responses can display it',
    (tester) async {
      final bridge = FakeBridge()..pending = Completer<Object?>();
      await mount(tester, bridge);
      await tester.scrollUntilVisible(
        find.text('Caută în Chimera'),
        350,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('Caută în Chimera'));
      await tester.pump();
      bridge.state['session'] = null;
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      bridge.pending!.complete(PreviewAssistBridge.matches);
      await tester.pumpAndSettle();
      expect(find.text('Bun venit în Chimera Assist'), findsOneWidget);
      expect(find.textContaining('Furnizor demonstrativ'), findsNothing);
    },
  );
  testWidgets(
    'lookup errors and empty results are visible and retry remains available',
    (tester) async {
      final bridge = FakeBridge('error');
      await mount(tester, bridge);
      await tap(tester, 'Caută în Chimera');
      expect(find.textContaining('Serviciul nu este disponibil'), findsWidgets);
      await tester.pumpWidget(const SizedBox());
      await mount(tester, FakeBridge('empty'));
      await tap(tester, 'Caută în Chimera');
      expect(find.textContaining('Numărul nu corespunde'), findsOneWidget);
    },
  );
  testWidgets('overlay action waits for an in-flight manual lookup', (
    tester,
  ) async {
    final bridge = FakeBridge()..pending = Completer<Object?>();
    await mount(tester, bridge);
    await tester.scrollUntilVisible(
      find.text('Caută în Chimera'),
      350,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('Caută în Chimera'));
    await tester.pump();
    bridge.state['selection'] = objects(
      PreviewAssistBridge.matches['partners'],
    ).single;
    bridge.state['selectedAction'] = 'invoices';
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(bridge.calls, isNot(contains('rows')));
    bridge.pending!.complete(PreviewAssistBridge.matches);
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(bridge.calls, contains('rows'));
    expect(find.text('Nu există înregistrări disponibile.'), findsOneWidget);
  });
  testWidgets(
    'native boundary does not return fake success on an unsupported platform',
    (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

      final bridge = NativeAssistBridge();
      expect(object(await bridge.call('state'))['platform'], 'unsupported');
      await expectLater(
        bridge.call('enable'),
        throwsA(isA<PlatformException>()),
      );
      debugDefaultTargetPlatformOverride = null;
    },
  );
}
