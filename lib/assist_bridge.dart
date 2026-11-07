import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

typedef Json = Map<String, dynamic>;
Json object(Object? value) => Map<String, dynamic>.from(value as Map? ?? {});
List<Json> objects(Object? value) =>
    (value as List? ?? []).map(object).toList();

abstract class AssistBridge {
  Future<Object?> call(String method, [Json arguments = const {}]);
}

class NativeAssistBridge implements AssistBridge {
  static const channel = MethodChannel('ro.chimeraerp.assist/native');
  @override
  Future<Object?> call(String method, [Json arguments = const {}]) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      if (method == 'state') {
        return {'platform': 'unsupported', 'session': null};
      }
      if (method == 'regions') return [];
      throw PlatformException(code: 'platform_pending');
    }
    return channel.invokeMethod<Object?>(method, arguments);
  }
}

/// Explicit development-only fixtures. No credentials, network requests or saved contacts.
class PreviewAssistBridge implements AssistBridge {
  final String scenario;
  PreviewAssistBridge(this.scenario);
  late Json state = {
    'platform': 'android',
    'session': scenario == 'welcome'
        ? null
        : {
            'organization': 'Firma demonstrativă',
            'origin': 'https://erp.example.test',
          },
    'enabled': scenario != 'setup',
    'running': scenario != 'setup',
    'roleAvailable': true,
    'role': scenario != 'setup',
    'overlay': scenario != 'setup',
    'contacts': scenario != 'setup',
    'phoneState': scenario != 'setup',
    'notifications': scenario != 'setup',
    'country': 'RO',
    'status': scenario == 'error'
        ? 'network_error'
        : scenario == 'setup'
        ? 'stopped'
        : 'ready',
    'lastCallTime': 0,
    'current': scenario == 'matched' ? matches : null,
  };
  static final Json matches = {
    'partners': [
      {
        'id': 'synthetic',
        'kind': 'supplier',
        'name': 'Furnizor demonstrativ',
        'tax_id': '',
        'actions': ['invoices', 'payments', 'products'],
      },
    ],
    'has_more': false,
  };
  late Json workspace = {
    'id': 1,
    'revision': 1,
    'lookup': matches,
    'cursor': {},
    'status': 'matched',
    'demo': scenario == 'demo',
  };
  @override
  Future<Object?> call(String method, [Json arguments = const {}]) async {
    switch (method) {
      case 'state':
        final result = {...state};
        if (arguments['consumeSelection'] == false) {
          result.remove('selection');
          result.remove('selectedAction');
        } else {
          state.remove('selection');
          state.remove('selectedAction');
        }
        return result;
      case 'regions':
        return [
          {'code': 'RO', 'name': 'România'},
          {'code': 'GB', 'name': 'United Kingdom'},
          {'code': 'DE', 'name': 'Deutschland'},
        ];
      case 'lookup':
        if (scenario == 'loading') {
          return Future<Object?>.delayed(const Duration(minutes: 5));
        }
        if (scenario == 'error') throw PlatformException(code: 'network_error');
        return scenario == 'empty'
            ? {'partners': [], 'has_more': false}
            : matches;
      case 'workspace':
        return workspace;
      case 'navigate':
        workspace['cursor'] = {...arguments};
        return null;
      case 'rows':
        if (scenario == 'panel-loading') return Completer<Object?>().future;
        if (scenario == 'panel-error') {
          throw PlatformException(code: 'network_error');
        }
        if (!scenario.startsWith('panel') || scenario == 'panel-empty') {
          return {'rows': [], 'has_more': false};
        }
        final start = (arguments['offset'] as num?)?.toInt() ?? 0;
        final action = arguments['action'];
        return {
          'rows': List.generate(
            start == 0 ? 25 : 3,
            (i) => {
              'id': 'synthetic_${start + i}',
              'label': action == 'products'
                  ? 'Făină pentru patiserie ${start + i + 1}'
                  : action == 'payments'
                  ? 'Plată OP ${start + i + 1}'
                  : 'FCT 2026 / ${start + i + 1}',
              'detail': action == 'products'
                  ? 'SAC 25 KG · Produs activ'
                  : '07.11.2026 · Înregistrată',
              'amount': action == 'products' ? null : '1240.00',
              'currency': action == 'products' ? null : 'RON',
              if (action == 'invoices') 'outstanding': '840.00',
            },
          ),
          'has_more': start == 0,
        };
      case 'detail':
        final action = arguments['action'];
        final record =
            (int.tryParse(arguments['item'].toString().split('_').last) ?? 0) +
            1;
        return {
          'id': arguments['item'],
          'title': action == 'products'
              ? 'Făină pentru patiserie'
              : action == 'payments'
              ? 'Plată OP 1'
              : 'FCT 2026 / $record',
          'fields': action == 'products'
              ? [
                  {
                    'key': 'description',
                    'value': 'Făină albă pentru patiserie, ambalată în saci.',
                  },
                  {'key': 'unit', 'value': 'kg'},
                  {'key': 'pack_size', 'value': '25'},
                  {'key': 'minimum_order_quantity', 'value': '50'},
                ]
              : [
                  {'key': 'date', 'value': '07.11.2026'},
                  {
                    'key': action == 'payments' ? 'amount' : 'total',
                    'value': '1240.00',
                  },
                  {'key': 'currency', 'value': 'RON'},
                  if (action == 'invoices')
                    {'key': 'outstanding', 'value': '840.00'},
                  if (action == 'payments')
                    {'key': 'reference', 'value': 'OP-2026-001'},
                ],
          'lines': action != 'invoices'
              ? []
              : List.generate(
                  3,
                  (i) => {
                    'id': 'line_$i',
                    'label': 'Produs demonstrativ ${i + 1}',
                    'fields': [
                      {'key': 'quantity', 'value': '10'},
                      {'key': 'unit_price', 'value': '20.00'},
                      {'key': 'total_price', 'value': '200.00'},
                    ],
                  },
                ),
          'has_more': action == 'invoices' && arguments['offset'] == 0,
        };
      case 'region':
        state['country'] = arguments['country'] ?? 'RO';
        state['countryOverride'] = arguments['country'];
        return state;
      case 'testOverlay':
        state['status'] = 'overlay_shown';
        return null;
      case 'testNumber':
        state['current'] = matches;
        state['status'] = 'overlay_shown';
        return null;
      case 'enable':
        state['enabled'] = true;
        state['running'] = true;
        return null;
      case 'disable':
        state['enabled'] = false;
        state['running'] = false;
        return state;
      case 'logout':
        state['session'] = null;
        return state;
      default:
        return null;
    }
  }
}
