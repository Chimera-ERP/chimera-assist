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
      case 'rows':
        return {'rows': [], 'has_more': false};
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
