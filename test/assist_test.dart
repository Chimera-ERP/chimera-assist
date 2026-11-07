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
  await tester.pumpAndSettle();
  await Scrollable.ensureVisible(tester.element(target), alignment: 0.5);
  await tester.pumpAndSettle();
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
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'https://',
      );
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
    'saved self-hosted destination survives logout, expiry and polling while editing',
    (tester) async {
      final bridge = FakeBridge();
      bridge.state.addAll({
        'enterpriseLogin': true,
        'enterpriseOrigin': 'https://erp.example.test',
      });
      await mount(tester, bridge);
      await tap(tester, 'Ieșire din cont');
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'https://erp.example.test',
      );
      await tester.enterText(
        find.byType(TextField),
        'https://another.example.test',
      );
      await tester.pump(const Duration(seconds: 2));
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'https://another.example.test',
      );
      await tester.pumpWidget(const SizedBox());
      bridge.state['status'] = 'session_expired';
      await mount(tester, bridge);
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'https://erp.example.test',
      );
      await tap(tester, 'Autentificare');
      expect(bridge.args[bridge.calls.indexOf('login')], {
        'origin': 'https://erp.example.test',
      });
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'legacy session explains the one-time login without exposing credentials',
    (tester) async {
      final bridge = FakeBridge('welcome');
      bridge.state['status'] = 'session_upgrade_required';
      await mount(tester, bridge);
      expect(find.textContaining('o dată după actualizare'), findsOneWidget);
      expect(find.text('Autentificare'), findsOneWidget);
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
    'manual partner opens the real overlay instead of a full-screen record page',
    (tester) async {
      final bridge = FakeBridge();
      await mount(tester, bridge, ro: false);
      await tap(tester, 'Find in Chimera');
      await tap(tester, 'Furnizor demonstrativ · Supplier');
      final i = bridge.calls.indexOf('openWorkspace');
      expect(bridge.args[i], {
        'kind': 'supplier',
        'id': 'synthetic',
        'manual': true,
      });
      expect(bridge.calls, isNot(contains('rows')));
      expect(find.text('Partners'), findsOneWidget);
      expect(find.text('Invoices'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'current caller uses the overlay without resetting its saved action',
    (tester) async {
      final bridge = FakeBridge();
      bridge.state['current'] = PreviewAssistBridge.matches;
      await mount(tester, bridge);
      await tap(tester, 'Furnizor demonstrativ · Furnizor');
      final i = bridge.calls.indexOf('openWorkspace');
      expect(bridge.args[i], {
        'kind': 'supplier',
        'id': 'synthetic',
        'manual': false,
      });
      expect(find.text('Facturi'), findsNothing);
      expect(bridge.calls, isNot(contains('rows')));
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
  testWidgets(
    'new call leaves manual lookup in place and opens only the floating panel on request',
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
      bridge.state['current'] = PreviewAssistBridge.matches;
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(bridge.calls, isNot(contains('rows')));
      bridge.pending!.complete(PreviewAssistBridge.matches);
      await tester.pumpAndSettle();
      expect(find.text('Parteneri'), findsOneWidget);
      expect(bridge.calls, isNot(contains('rows')));
    },
  );
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
