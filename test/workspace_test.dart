import 'dart:async';
import 'package:chimera_assist/assist_bridge.dart';
import 'package:chimera_assist/workspace.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class WorkspaceFixture extends PreviewAssistBridge {
  WorkspaceFixture([super.scenario = 'panel']);
  final requests = <Json>[];
  Completer<Object?>? pending;
  @override
  Future<Object?> call(String method, [Json arguments = const {}]) {
    requests.add({'method': method, ...arguments});
    if (method == 'detail' && pending != null) return pending!.future;
    return super.call(method, arguments);
  }
}

Future<void> mountPanel(
  WidgetTester tester,
  WorkspaceFixture bridge, {
  double scale = 1,
  Size size = const Size(360, 600),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      home: MediaQuery(
        data: MediaQueryData(size: size, textScaler: TextScaler.linear(scale)),
        child: CallerWorkspace(bridge: bridge, romanian: true),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> tapPanel(WidgetTester tester, String text) async {
  await tester.tap(find.text(text).first);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'transparent space around the compact menu minimizes and keeps navigation',
    (tester) async {
      final bridge = WorkspaceFixture();
      await mountPanel(tester, bridge, size: const Size(336, 460));
      await tapPanel(tester, 'Facturi');
      await tapPanel(tester, 'FCT 2026 / 1');
      final saved = Map<String, dynamic>.from(
        object(bridge.workspace['cursor']),
      );
      await tester.tapAt(const Offset(2, 2));
      await tester.pump();
      expect(bridge.requests.last['method'], 'minimize');
      expect(bridge.workspace['cursor'], saved);
    },
  );
  testWidgets('large-text narrow menu keeps all touch targets usable', (
    tester,
  ) async {
    final bridge = WorkspaceFixture();
    final partner = objects(
      object(bridge.workspace['lookup'])['partners'],
    ).single;
    partner['name'] = 'Furnizor demonstrativ cu denumire comercială lungă';
    bridge.workspace['lookup'] = {
      'partners': [partner],
      'has_more': false,
    };
    await mountPanel(tester, bridge, scale: 2, size: const Size(320, 600));
    for (final label in ['Facturi', 'Plăți', 'Produse']) {
      final control = find
          .ancestor(of: find.text(label), matching: find.byType(TextButton))
          .first;
      final size = tester.getSize(control);
      expect(size.width, greaterThanOrEqualTo(48));
      expect(size.height, greaterThanOrEqualTo(48));
    }
    expect(tester.takeException(), isNull, reason: 'scaled compact menu');
    await tapPanel(tester, 'Facturi');
    expect(find.text('FCT 2026 / 1'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('menu is compact and detail expansion stays bounded', (
    tester,
  ) async {
    final bridge = WorkspaceFixture();
    await mountPanel(tester, bridge, size: const Size(390, 844));
    final surface = find
        .ancestor(
          of: find.text('Furnizor demonstrativ'),
          matching: find.byType(Material),
        )
        .first;
    expect(tester.getSize(surface).height, lessThan(240));
    expect(find.textContaining('Alege o acțiune'), findsNothing);
    await tapPanel(tester, 'Facturi');
    expect(tester.getSize(surface).height, lessThan(480));
    expect(find.text('FCT 2026 / 1'), findsOneWidget);
    await tapPanel(tester, 'FCT 2026 / 1');
    expect(tester.getSize(surface).height, lessThan(480));
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'header actions, pagination and invoice detail stay in one floating workspace',
    (tester) async {
      final bridge = WorkspaceFixture();
      await mountPanel(tester, bridge);
      expect(find.text('Close'), findsNothing);
      expect(find.text('Închide'), findsNothing);
      await tapPanel(tester, 'Facturi');
      await tester.tap(find.byTooltip('Pagina următoare'));
      await tester.pumpAndSettle();
      expect(find.text('Pagina 2'), findsOneWidget);
      await tapPanel(tester, 'FCT 2026 / 26');
      expect(find.text('Înapoi la listă'), findsOneWidget);
      expect(find.text('Total'), findsOneWidget);
      expect(
        bridge.requests.lastWhere((r) => r['method'] == 'detail')['item'],
        'synthetic_25',
      );
      await tapPanel(tester, 'Înapoi la listă');
      expect(find.text('Pagina 2'), findsOneWidget);
      expect(find.text('FCT 2026 / 26'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'minimize and caller A B A restore A detail without full-screen navigation',
    (tester) async {
      final bridge = WorkspaceFixture();
      await mountPanel(tester, bridge);
      await tapPanel(tester, 'Facturi');
      await tapPanel(tester, 'FCT 2026 / 1');
      final saved = {
        ...bridge.workspace,
        'cursor': {...object(bridge.workspace['cursor'])},
      };
      await tester.tap(find.byTooltip('Minimizează'));
      await tester.pumpAndSettle();
      expect(bridge.requests.any((r) => r['method'] == 'minimize'), isTrue);
      expect(find.text('Înapoi la listă'), findsOneWidget);
      bridge.workspace = {
        'id': 2,
        'revision': 2,
        'cursor': {},
        'lookup': {
          'partners': [
            {
              'kind': 'client',
              'id': 'client_b',
              'name': 'Client B',
              'actions': ['payments'],
            },
          ],
          'has_more': false,
        },
      };
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.text('Client B'), findsOneWidget);
      expect(find.text('FCT 2026 / 1'), findsNothing);
      bridge.workspace = {...saved, 'revision': 3};
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.text('Înapoi la listă'), findsOneWidget);
      expect(
        bridge.requests.lastWhere((r) => r['method'] == 'detail')['item'],
        'synthetic_0',
      );
      expect(
        bridge.requests.any(
          (r) => ['login', 'openWorkspace', 'lookup'].contains(r['method']),
        ),
        isFalse,
      );
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('late record response cannot replace a different caller', (
    tester,
  ) async {
    final bridge = WorkspaceFixture();
    await mountPanel(tester, bridge);
    await tapPanel(tester, 'Facturi');
    bridge.pending = Completer<Object?>();
    await tester.tap(find.text('FCT 2026 / 1'));
    await tester.pump();
    bridge.workspace = {
      'id': 2,
      'revision': 2,
      'cursor': {},
      'lookup': {
        'partners': [
          {
            'kind': 'client',
            'id': 'other',
            'name': 'Other caller',
            'actions': ['products'],
          },
        ],
        'has_more': false,
      },
    };
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump();
    bridge.pending!.complete({
      'id': 'old',
      'title': 'Old private detail',
      'fields': [],
      'lines': [],
      'has_more': false,
    });
    await tester.pumpAndSettle();
    expect(find.text('Old private detail'), findsNothing);
    expect(find.text('Other caller'), findsOneWidget);
  });
  testWidgets(
    'payment and product records have their own details with usable large text',
    (tester) async {
      final bridge = WorkspaceFixture();
      await mountPanel(tester, bridge, scale: 1.4);
      expect(tester.takeException(), isNull, reason: "large text menu");
      await tapPanel(tester, 'Plăți');
      expect(tester.takeException(), isNull, reason: 'payment list');
      await tapPanel(tester, 'Plată OP 1');
      expect(tester.takeException(), isNull, reason: 'payment detail');
      expect(find.text('Sumă'), findsOneWidget);
      expect(find.text('OP-2026-001'), findsOneWidget);
      await tapPanel(tester, 'Produse');
      await tapPanel(tester, 'Făină pentru patiserie 1');
      expect(find.text('Descriere'), findsOneWidget);
      expect(find.text('Ambalaj'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'short landscape window keeps header and details within its bounds',
    (tester) async {
      await mountPanel(tester, WorkspaceFixture(), size: const Size(392, 264));
      await tapPanel(tester, 'Facturi');
      await tapPanel(tester, 'FCT 2026 / 1');
      expect(find.text('Înapoi la listă'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'ambiguous caller can change the selected partner in the header',
    (tester) async {
      final bridge = WorkspaceFixture();
      final partners = objects(object(bridge.workspace['lookup'])['partners']);
      bridge.workspace['lookup'] = {
        'partners': [
          ...partners,
          {...partners.first, 'id': 'second', 'name': 'Al doilea partener'},
        ],
      };
      await mountPanel(tester, bridge);
      await tapPanel(tester, 'Furnizor demonstrativ');
      await tapPanel(tester, 'Facturi');
      await tester.tap(find.byTooltip('Schimbă partenerul'));
      await tester.pumpAndSettle();
      await tapPanel(tester, 'Al doilea partener');
      expect(find.text('Al doilea partener'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  for (final scenario in ['panel-empty', 'panel-error', 'panel-loading']) {
    testWidgets('floating $scenario state is visible and can switch action', (
      tester,
    ) async {
      final bridge = WorkspaceFixture(scenario);
      await mountPanel(tester, bridge);
      await tester.tap(find.text('Facturi'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 150));
      if (scenario == 'panel-empty') {
        expect(find.text('Nicio înregistrare.'), findsOneWidget);
      }
      if (scenario == 'panel-error') {
        expect(find.text('Reîncearcă'), findsOneWidget);
      }
      if (scenario == 'panel-loading') {
        expect(find.byType(CircularProgressIndicator), findsOneWidget);
      }
      expect(find.text('Plăți'), findsOneWidget);
      expect(find.text('Produse'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
