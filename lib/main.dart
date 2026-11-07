import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'assist_bridge.dart';
import 'workspace.dart';

void main() {
  const preview = bool.fromEnvironment('ASSIST_PREVIEW');
  if (kDebugMode && preview) {
    // Keep the development browser preview accessible for UI inspection.
    WidgetsFlutterBinding.ensureInitialized().ensureSemantics();
  }
  runApp(
    AssistApp(
      romanian:
          kDebugMode && preview && Uri.base.queryParameters['lang'] == 'ro'
          ? true
          : null,
      bridge: kDebugMode && preview
          ? PreviewAssistBridge(Uri.base.queryParameters['state'] ?? 'setup')
          : NativeAssistBridge(),
    ),
  );
}

class AssistApp extends StatelessWidget {
  final AssistBridge bridge;
  final bool? romanian;
  const AssistApp({super.key, required this.bridge, this.romanian});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Chimera Assist',
    initialRoute: '/',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff5144d3)),
      scaffoldBackgroundColor: const Color(0xfff7f7fb),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
      ),
    ),
    home:
        kDebugMode &&
            const bool.fromEnvironment('ASSIST_PREVIEW') &&
            Uri.base.queryParameters['overlay'] == '1'
        ? WorkspacePreview(bridge: bridge, romanian: romanian)
        : WidgetsBinding.instance.platformDispatcher.defaultRouteName ==
              '/overlay'
        ? Scaffold(
            backgroundColor: Colors.transparent,
            body: CallerWorkspace(bridge: bridge, romanian: romanian),
          )
        : AssistHome(bridge: bridge, romanian: romanian),
  );
}

class AssistHome extends StatefulWidget {
  final AssistBridge bridge;
  final bool? romanian;
  const AssistHome({super.key, required this.bridge, this.romanian});
  @override
  State<AssistHome> createState() => _AssistHomeState();
}

class _AssistHomeState extends State<AssistHome> with WidgetsBindingObserver {
  Json? state;
  final phone = TextEditingController(), server = TextEditingController();
  Timer? poll;
  bool busy = false, reading = false, enterprise = false;
  String? error;
  int request = 0;
  String page = 'home';
  Json? lookup, partner;
  bool get ro =>
      widget.romanian ??
      WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'ro';
  String t(String romanian, String english) => ro ? romanian : english;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    startPoll();
  }

  void startPoll() {
    poll?.cancel();
    poll = Timer.periodic(const Duration(seconds: 1), (_) => refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    if (value == AppLifecycleState.resumed) {
      refresh();
      startPoll();
    } else {
      poll?.cancel();
    }
  }

  @override
  void dispose() {
    request++;
    poll?.cancel();
    phone.dispose();
    server.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> refresh() async {
    if (reading) return;
    reading = true;
    try {
      final next = object(
        await widget.bridge.call('state', {'consumeSelection': !busy}),
      );
      if (!mounted) return;
      final changedSession =
          state?['session'] != null && next['session'] == null;
      setState(() {
        state = next;
        if (changedSession) {
          request++;
          busy = false;
          page = 'home';
          lookup = partner = null;
        }
      });
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.code);
    } finally {
      reading = false;
    }
  }

  Future<void> perform(Future<void> Function() work) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await work();
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.code);
    } catch (_) {
      if (mounted) setState(() => error = 'network_error');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> command(String method, [Json args = const {}]) =>
      perform(() async {
        await widget.bridge.call(method, args);
        await refresh();
      });
  String message(String? code) => switch (code) {
    'ready' || 'signed_in' => t(
      'Pregătit pentru identificare.',
      'Ready for caller identification.',
    ),
    'stopped' => t(
      'Asistent oprit. Activează-l pentru apeluri.',
      'Assistant stopped. Activate it for calls.',
    ),
    'received' => t(
      'Android a transmis un apel către Assist.',
      'Android delivered a call to Assist.',
    ),
    'looking' => t(
      'Caut partenerul în Chimera…',
      'Finding the partner in Chimera…',
    ),
    'matched' => t(
      'Partener găsit. Detaliile sunt disponibile mai jos.',
      'Partner found. Details are available below.',
    ),
    'overlay_shown' => t(
      'Android a acceptat afișarea cercului.',
      'Android accepted the circle overlay.',
    ),
    'overlay_denied' => t(
      'Permite afișarea peste alte aplicații.',
      'Allow display over other apps.',
    ),
    'overlay_failed' => t(
      'Android a refuzat cercul. Verifică permisiunea de afișare; detaliile rămân în aplicație.',
      'Android refused the circle. Check overlay permission; details remain in the app.',
    ),
    'locked' => t(
      'Telefon blocat. Cercul poate apărea după deblocare.',
      'Phone locked. The circle can appear after unlocking.',
    ),
    'no_match' => t(
      'Numărul nu corespunde unui partener disponibil contului tău.',
      'No partner for this number is available to your account.',
    ),
    'start_blocked' => t(
      'Android a oprit serviciul. Apasă Activează pentru a-l relua.',
      'Android stopped the service. Tap Activate to resume.',
    ),
    'invalid_phone' => t(
      'Verifică numărul complet și țara selectată. Poți folosi orice prefix internațional.',
      'Check the complete number and selected country. Any international calling code is supported.',
    ),
    'session_expired' => t(
      'Sesiunea a expirat. Autentifică-te din nou în browser.',
      'Session expired. Sign in again through your browser.',
    ),
    'forbidden' => t(
      'Contul nu are acces la aceste date.',
      'Your account cannot access this data.',
    ),
    'login_cancelled' => t(
      'Autentificarea a fost anulată.',
      'Sign-in was cancelled.',
    ),
    'login_failed' => t(
      'Autentificarea nu s-a finalizat. Reîncearcă.',
      'Sign-in did not complete. Try again.',
    ),
    'platform_pending' => t(
      'Integrarea iOS este în pregătire. Această versiune funcționează pe Android.',
      'The iOS integration is in preparation. This release works on Android.',
    ),
    'cancelled' => t('Operația a fost anulată.', 'Operation cancelled.'),
    'network_error' => t(
      'Serviciul nu este disponibil. Verifică internetul și reîncearcă.',
      'Service unavailable. Check your connection and retry.',
    ),
    _ => t(
      'Activează asistentul și testează cercul.',
      'Activate the assistant and test the circle.',
    ),
  };
  Widget gap([double height = 16]) => SizedBox(height: height);
  Widget heading(String text) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Text(text, style: Theme.of(context).textTheme.titleLarge),
  );
  Widget button(
    String text,
    VoidCallback? onPressed, {
    IconData? icon,
    bool primary = false,
  }) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: SizedBox(
      width: double.infinity,
      child: primary
          ? FilledButton(
              onPressed: busy ? null : onPressed,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(text),
              ),
            )
          : OutlinedButton.icon(
              onPressed: busy ? null : onPressed,
              icon: Icon(icon ?? Icons.chevron_right),
              label: Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(text),
              ),
            ),
    ),
  );
  Widget permission(String key, String title, String detail) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: Icon(
      state?[key] == true ? Icons.check_circle : Icons.warning_amber,
      color: state?[key] == true
          ? Colors.green.shade700
          : Colors.orange.shade800,
    ),
    title: Text(title),
    subtitle: Text(detail),
    trailing: const Icon(Icons.chevron_right),
    onTap: busy ? null : () => command('permission', {'kind': key}),
  );
  Future<void> country() async {
    List<Json> countries;
    try {
      countries = objects(await widget.bridge.call('regions'));
    } catch (_) {
      if (mounted) setState(() => error = 'network_error');
      return;
    }
    if (!mounted) return;
    final selected = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (context) {
        String query = '';
        return StatefulBuilder(
          builder: (context, update) => SafeArea(
            child: SizedBox(
              height: MediaQuery.sizeOf(context).height * .8,
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    Text(
                      t(
                        'Țara numerelor fără prefix',
                        'Country for numbers without a prefix',
                      ),
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    gap(),
                    TextField(
                      decoration: InputDecoration(
                        labelText: t('Caută țara', 'Search country'),
                      ),
                      onChanged: (value) =>
                          update(() => query = value.toLowerCase()),
                    ),
                    Expanded(
                      child: ListView(
                        children: [
                          ListTile(
                            title: Text(
                              t(
                                'Automat: rețea, SIM sau regiunea telefonului',
                                'Automatic: network, SIM or phone region',
                              ),
                            ),
                            onTap: () => Navigator.pop(context, 'auto'),
                          ),
                          for (final item in countries.where(
                            (e) => '${e['name']} ${e['code']}'
                                .toLowerCase()
                                .contains(query),
                          ))
                            ListTile(
                              title: Text('${item['name']} (${item['code']})'),
                              onTap: () => Navigator.pop(
                                context,
                                item['code'] as String,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected != null) {
      await command('region', {
        'country': selected == 'auto' ? null : selected,
      });
    }
  }

  Future<void> search() => perform(() async {
    final id = ++request;
    final result = object(
      await widget.bridge.call('lookup', {'phone': phone.text.trim()}),
    );
    if (mounted && id == request) {
      setState(() {
        lookup = result;
        page = 'lookup';
      });
    }
  });
  List<Widget> partners(Json? value) => [
    if (objects(value?['partners']).isEmpty) Text(message('no_match')),
    for (final item in objects(value?['partners']))
      button(
        '${item['name']} · ${item['kind'] == 'supplier' ? t('Furnizor', 'Supplier') : t('Client', 'Client')}',
        () => setState(() {
          partner = item;
          page = 'partner';
          error = null;
        }),
      ),
    if (value?['has_more'] == true)
      Text(
        t(
          'Peste 20 de parteneri folosesc acest număr. Verifică datele în ERP.',
          'More than 20 partners share this number. Review the ERP records.',
        ),
      ),
  ];
  List<Widget> welcome() => [
    gap(50),
    Center(
      child: Image.asset('assets/chimera_logo.png', width: 104, height: 104),
    ),
    gap(28),
    Text(
      t('Bun venit în Chimera Assist', 'Welcome to Chimera Assist'),
      textAlign: TextAlign.center,
      style: Theme.of(context).textTheme.headlineMedium,
    ),
    gap(),
    Text(
      t(
        'Contextul afacerii tale, când sună telefonul.',
        'Your business context, when the phone rings.',
      ),
      textAlign: TextAlign.center,
    ),
    gap(32),
    Text(
      enterprise
          ? t('Serverul tău Chimera', 'Your Chimera server')
          : 'Chimera Cloud',
      textAlign: TextAlign.center,
    ),
    gap(),
    if (enterprise)
      TextField(
        controller: server,
        keyboardType: TextInputType.url,
        autocorrect: false,
        decoration: InputDecoration(
          labelText: t('Adresa serverului', 'Server address'),
          hintText: 'https://chimera.firma.ro',
        ),
      ),
    if (state?['platform'] == 'unsupported') Text(message('platform_pending')),
    button(
      t('Autentificare', 'Log in'),
      state?['platform'] == 'unsupported' || state?['loginBusy'] == true
          ? null
          : () => command(
              'login',
              enterprise ? {'origin': server.text.trim()} : {},
            ),
      primary: true,
    ),
    TextButton(
      onPressed: busy ? null : () => setState(() => enterprise = !enterprise),
      child: Text(
        enterprise
            ? t('Folosește Chimera Cloud', 'Use Chimera Cloud')
            : 'Enterprise / Self-hosted',
      ),
    ),
    if (state?['loginBusy'] == true)
      Text(
        t(
          'Finalizează autentificarea în browser.',
          'Finish signing in through your browser.',
        ),
      ),
    if ([
      'session_expired',
      'login_failed',
      'login_cancelled',
    ].contains(state?['status']))
      Text(message(state?['status'])),
  ];
  List<Widget> home() {
    final session = object(state?['session']);
    final callback = state?['lastCallTime'] as int? ?? 0;
    final time = callback == 0
        ? null
        : DateTime.fromMillisecondsSinceEpoch(callback).toLocal();
    return [
      Text(
        session['organization'] as String? ?? '',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      Text(session['origin'] as String? ?? ''),
      gap(),
      Row(
        children: [
          Icon(
            state?['enabled'] == true && state?['running'] == true
                ? Icons.radio_button_checked
                : Icons.pause_circle_outline,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              state?['enabled'] == true && state?['running'] == true
                  ? t('Asistent activ', 'Assistant active')
                  : t('Asistent oprit', 'Assistant stopped'),
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
        ],
      ),
      gap(8),
      Text(message(state?['status'])),
      gap(8),
      Text(
        time == null
            ? t(
                'Android nu a transmis încă niciun apel către această versiune.',
                'Android has not delivered a call to this version yet.',
              )
            : '${t('Ultimul apel transmis de Android: ', 'Last call delivered by Android: ')}${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}',
      ),
      if (state?['current'] != null) ...[
        heading(t('Contextul apelului', 'Call context')),
        ...partners(object(state?['current'])),
        button(
          t('Deschide panoul plutitor', 'Open floating panel'),
          () => command('openWorkspace'),
        ),
      ],
      if (state?['current'] != null)
        Text(
          t(
            'Dacă aplicația Telefon ascunde cercul, poți deschide detaliile aici sau din notificarea Assist.',
            'If the Phone app hides the circle, open details here or from the Assist notification.',
          ),
        ),
      button(
        state?['enabled'] == true
            ? t('Oprește asistentul', 'Stop assistant')
            : t('Activează asistentul', 'Activate assistant'),
        state?['enabled'] == true
            ? () => command('disable')
            : state?['role'] == true &&
                  state?['overlay'] == true &&
                  state?['phoneState'] == true
            ? () => command('enable')
            : null,
        primary: true,
      ),
      button(
        t('Testează cercul', 'Test the circle'),
        state?['overlay'] == true ? () => command('testOverlay') : null,
        icon: Icons.bubble_chart_outlined,
      ),
      Text(
        t(
          'Testul cercului nu face un apel și nu caută date. Apasă cercul pentru a-l deschide; atinge în afara panoului pentru a-l minimiza.',
          'The circle test makes no call and looks up no data. Tap the circle to open it; tap outside the panel to minimize.',
        ),
      ),
      heading(t('Permisiuni Android', 'Android permissions')),
      permission(
        'role',
        t('Identificarea apelantului', 'Caller identification'),
        state?['roleAvailable'] == false
            ? t(
                'Indisponibilă pe acest dispozitiv',
                'Unavailable on this device',
              )
            : t(
                'Assist trebuie selectat în setările Android.',
                'Assist must be selected in Android settings.',
              ),
      ),
      permission(
        'overlay',
        t('Afișare peste alte aplicații', 'Display over other apps'),
        t(
          'Necesară pentru cercul Chimera.',
          'Required for the Chimera circle.',
        ),
      ),
      permission(
        'phoneState',
        t('Starea apelului', 'Call status'),
        t(
          'Păstrează contextul pe toată durata apelului și încă 30 de minute după.',
          'Keep context throughout the call and for 30 minutes afterwards.',
        ),
      ),
      permission(
        'contacts',
        t('Apeluri de la numere salvate', 'Calls from saved numbers'),
        t(
          'Android cere această permisiune pentru a transmite și aceste apeluri. Assist nu citește agenda.',
          'Android requires this permission to deliver these calls too. Assist does not read your address book.',
        ),
      ),
      permission(
        'notifications',
        t('Notificări', 'Notifications'),
        t(
          'Starea asistentului și acces la detaliile apelului.',
          'Assistant status and access to call details.',
        ),
      ),
      const Divider(),
      heading(t('Caută sau simulează un apel', 'Find a number or test a call')),
      TextField(
        controller: phone,
        keyboardType: TextInputType.phone,
        decoration: InputDecoration(
          labelText: t('Număr de telefon', 'Phone number'),
          hintText: t(
            'Număr național sau internațional',
            'National or international number',
          ),
        ),
      ),
      gap(8),
      ListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(
          t(
            'Țara numerelor fără prefix',
            'Country for numbers without a prefix',
          ),
        ),
        subtitle: Text(
          '${state?['countryOverride'] == null ? t('Automat', 'Automatic') : t('Selectată', 'Selected')}: ${state?['country'] ?? t('alege țara', 'choose country')}',
        ),
        trailing: const Icon(Icons.expand_more),
        onTap: country,
      ),
      Text(
        t(
          'Prefixele internaționale (+49, +44, +1 etc.) se păstrează. Fără prefix folosim țara de mai sus, nu presupunem România.',
          'International prefixes (+49, +44, +1 etc.) are preserved. Without a prefix we use the country above, not a Romanian default.',
        ),
      ),
      button(
        t('Caută în Chimera', 'Find in Chimera'),
        search,
        icon: Icons.search,
      ),
      button(
        t('Testează cu acest număr', 'Test with this number'),
        state?['overlay'] == true
            ? () => command('testNumber', {'phone': phone.text.trim()})
            : null,
        icon: Icons.phone_in_talk_outlined,
      ),
      Text(
        t(
          'Folosește aceeași căutare și același cerc ca un apel primit; nu dovedește că Android transmite apelul real.',
          'Uses the same lookup and circle as an incoming call; it does not prove Android delivered a real call.',
        ),
      ),
      gap(24),
      TextButton(
        onPressed: busy ? null : () => command('logout'),
        child: Text(t('Ieșire din cont', 'Sign out')),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final signedIn = state?['session'] != null;
    return PopScope(
      canPop: page == 'home',
      onPopInvokedWithResult: (popped, _) {
        if (!popped) {
          setState(() {
            request++;
            busy = false;
            page = 'home';
            error = null;
          });
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Chimera Assist'),
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(4),
            child: busy
                ? LinearProgressIndicator(
                    semanticsLabel: t('Se încarcă', 'Loading'),
                  )
                : const SizedBox(height: 4),
          ),
          leading: page == 'home'
              ? null
              : IconButton(
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => setState(() {
                    request++;
                    busy = false;
                    page = 'home';
                    error = null;
                  }),
                ),
        ),
        body: SafeArea(
          child: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 580),
              child: ListView(
                key: ValueKey(page),
                padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
                children: [
                  if (state == null)
                    const Center(child: CircularProgressIndicator())
                  else if (!signedIn)
                    ...welcome()
                  else if (page == 'home')
                    ...home()
                  else if (page == 'lookup') ...[
                    heading(t('Parteneri', 'Partners')),
                    ...partners(lookup),
                  ] else if (page == 'partner' && partner != null) ...[
                    SizedBox(
                      height: (MediaQuery.sizeOf(context).height - 160).clamp(
                        420,
                        650,
                      ),
                      child: RecordWorkspace(
                        bridge: widget.bridge,
                        partners: [partner!],
                        romanian: ro,
                      ),
                    ),
                  ],
                  if (error != null)
                    Semantics(
                      liveRegion: true,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        child: Text(
                          message(error),
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
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
