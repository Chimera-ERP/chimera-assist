import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'assist_bridge.dart';

/// The same content renderer is used by the Android floating surface and app reads.
class CallerWorkspace extends StatefulWidget {
  final AssistBridge bridge;
  final bool? romanian;
  const CallerWorkspace({super.key, required this.bridge, this.romanian});
  @override
  State<CallerWorkspace> createState() => _CallerWorkspaceState();
}

class _CallerWorkspaceState extends State<CallerWorkspace>
    with WidgetsBindingObserver {
  Json state = {};
  Timer? timer;
  bool reading = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    timer = Timer.periodic(const Duration(milliseconds: 400), (_) => refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    timer?.cancel();
    if (value == AppLifecycleState.resumed) {
      refresh();
      timer = Timer.periodic(
        const Duration(milliseconds: 400),
        (_) => refresh(),
      );
    }
  }

  Future<void> refresh() async {
    if (reading) return;
    reading = true;
    try {
      final value = object(await widget.bridge.call('workspace'));
      if (mounted) setState(() => state = value);
    } on PlatformException catch (_) {
      if (mounted) setState(() => state = {});
    } finally {
      reading = false;
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => RecordWorkspace(
    key: ValueKey(state['id'] ?? (state['demo'] == true ? 'demo' : 'none')),
    bridge: widget.bridge,
    contextId: state['id'],
    revision: state['revision'],
    partners: objects(object(state['lookup'])['partners']),
    cursor: object(state['cursor']),
    romanian: widget.romanian,
    demo: state['demo'] == true,
    status: state['status'] as String?,
    floating: true,
  );
}

class RecordWorkspace extends StatefulWidget {
  final AssistBridge bridge;
  final List<Json> partners;
  final Json cursor;
  final Object? contextId, revision;
  final bool? romanian;
  final bool floating, demo;
  final String? status;
  const RecordWorkspace({
    super.key,
    required this.bridge,
    required this.partners,
    this.cursor = const {},
    this.contextId,
    this.revision,
    this.romanian,
    this.floating = false,
    this.demo = false,
    this.status,
  });
  @override
  State<RecordWorkspace> createState() => _RecordWorkspaceState();
}

class _RecordWorkspaceState extends State<RecordWorkspace> {
  Json? partner, rows, detail;
  String? action, item, error;
  int offset = 0, detailOffset = 0, generation = 0;
  bool loading = false;
  late ScrollController scroll;
  double listScroll = 0, detailScroll = 0;
  bool get ro =>
      widget.romanian ??
      WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'ro';
  String t(String a, String b) => ro ? a : b;
  String label(String key) => (ro ? _ro : _en)[key] ?? key;
  static const _ro = {
    'invoices': 'Facturi',
    'payments': 'Plăți',
    'products': 'Produse',
    'date': 'Data',
    'due_date': 'Scadență',
    'status': 'Stare',
    'payment_status': 'Achitare',
    'currency': 'Monedă',
    'subtotal': 'Fără TVA',
    'vat_amount': 'TVA',
    'total': 'Total',
    'outstanding': 'Rest de plată',
    'notes': 'Observații',
    'quantity': 'Cantitate',
    'unit_price': 'Preț unitar',
    'price_base_quantity': 'Cantitate de bază',
    'vat_percentage': 'TVA %',
    'total_price': 'Total linie',
    'external_code': 'Cod furnizor',
    'amount': 'Sumă',
    'direction': 'Sens',
    'method': 'Metodă',
    'reference': 'Referință',
    'reversal_reason': 'Motiv anulare',
    'description': 'Descriere',
    'category': 'Categorie',
    'unit': 'Unitate',
    'product_type': 'Tip produs',
    'active': 'Activ',
    'supplier_sku': 'Cod furnizor',
    'supplier_name': 'Denumire furnizor',
    'pack_size': 'Ambalaj',
    'minimum_order_quantity': 'Comandă minimă',
  };
  static const _en = {
    'invoices': 'Invoices',
    'payments': 'Payments',
    'products': 'Products',
    'date': 'Date',
    'due_date': 'Due date',
    'status': 'Status',
    'payment_status': 'Payment status',
    'currency': 'Currency',
    'subtotal': 'Subtotal',
    'vat_amount': 'VAT',
    'total': 'Total',
    'outstanding': 'Outstanding',
    'notes': 'Notes',
    'quantity': 'Quantity',
    'unit_price': 'Unit price',
    'price_base_quantity': 'Price base quantity',
    'vat_percentage': 'VAT %',
    'total_price': 'Line total',
    'external_code': 'Supplier code',
    'amount': 'Amount',
    'direction': 'Direction',
    'method': 'Method',
    'reference': 'Reference',
    'reversal_reason': 'Reversal reason',
    'description': 'Description',
    'category': 'Category',
    'unit': 'Unit',
    'product_type': 'Product type',
    'active': 'Active',
    'supplier_sku': 'Supplier code',
    'supplier_name': 'Supplier name',
    'pack_size': 'Pack size',
    'minimum_order_quantity': 'Minimum order',
  };
  @override
  void initState() {
    super.initState();
    scroll = ScrollController()..addListener(rememberScroll);
    restore();
  }

  void restore() {
    final cursor = widget.cursor;
    partner = widget.partners
        .where(
          (p) => p['id'] == cursor['partner'] && p['kind'] == cursor['kind'],
        )
        .firstOrNull;
    if (partner == null && widget.partners.length == 1) {
      partner = widget.partners.single;
    }
    action = cursor['action'] as String?;
    if (action != null &&
        !(partner?['actions'] as List? ?? []).contains(action)) {
      action = null;
    }
    offset = (cursor['offset'] as num?)?.toInt() ?? 0;
    item = cursor['item'] as String?;
    detailOffset = (cursor['detailOffset'] as num?)?.toInt() ?? 0;
    listScroll = (cursor['scroll'] as num?)?.toDouble() ?? 0;
    detailScroll = (cursor['detailScroll'] as num?)?.toDouble() ?? 0;
    if (action != null && partner != null) {
      load();
    } else {
      resize();
    }
  }

  @override
  void didUpdateWidget(RecordWorkspace old) {
    super.didUpdateWidget(old);
    if (old.revision != widget.revision ||
        old.partners.isEmpty && widget.partners.isNotEmpty) {
      generation++;
      rows = detail = null;
      error = null;
      restore();
    } else if (partner != null) {
      final matching = widget.partners
          .where(
            (p) => p['id'] == partner!['id'] && p['kind'] == partner!['kind'],
          )
          .firstOrNull;
      if (matching == null ||
          action != null && !(matching['actions'] as List).contains(action)) {
        generation++;
        partner = matching;
        action = item = null;
        rows = detail = null;
        loading = false;
        resize();
      }
    }
  }

  void resize() {
    if (!widget.floating) return;
    widget.bridge
        .call('resize', {
          'height': action == null
              ? (widget.partners.length > 1 ? 410 : 310)
              : item != null && detail != null && action != 'invoices'
              ? (290 + objects(detail?['fields']).length * 34).clamp(390, 600)
              : 600,
        })
        .catchError((_) => null);
  }

  Json get cursor => {
    'contextId': widget.contextId,
    'kind': partner?['kind'],
    'partner': partner?['id'],
    'action': action,
    'offset': offset,
    'item': item,
    'detailOffset': detailOffset,
    'scroll': listScroll,
    'detailScroll': detailScroll,
  };
  void remember() {
    if (widget.contextId == null || partner == null) return;
    widget.bridge.call('navigate', cursor).catchError((_) => null);
  }

  void rememberScroll() {
    if (!scroll.hasClients) return;
    if (item == null) {
      listScroll = scroll.offset;
    } else {
      detailScroll = scroll.offset;
    }
    remember();
  }

  Future<void> load() async {
    final selected = partner;
    if (selected == null || action == null) return;
    final request = ++generation;
    setState(() {
      loading = true;
      error = null;
    });
    remember();
    resize();
    try {
      final value = object(
        await widget.bridge.call(item == null ? 'rows' : 'detail', {
          if (widget.contextId != null) 'contextId': widget.contextId,
          'kind': selected['kind'],
          'id': selected['id'],
          'action': action,
          'offset': item == null ? offset : detailOffset,
          if (item != null) 'item': item,
        }),
      );
      if (!mounted || request != generation) return;
      setState(() {
        if (item == null) {
          rows = value;
        } else {
          detail = value;
        }
        loading = false;
      });
      resize();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && request == generation && scroll.hasClients) {
          scroll.jumpTo(
            (item == null ? listScroll : detailScroll).clamp(
              0,
              scroll.position.maxScrollExtent,
            ),
          );
        }
      });
    } on PlatformException catch (e) {
      if (mounted && request == generation) {
        setState(() {
          loading = false;
          error = e.code;
          rows = detail = null;
        });
      }
    } catch (_) {
      if (mounted && request == generation) {
        setState(() {
          loading = false;
          error = 'network_error';
          rows = detail = null;
        });
      }
    }
  }

  void choose(String value) {
    setState(() {
      action = value;
      item = null;
      offset = detailOffset = 0;
      listScroll = detailScroll = 0;
      rows = detail = null;
    });
    load();
  }

  void open(Json row) {
    setState(() {
      item = row['id'] as String;
      detail = null;
      detailOffset = 0;
      detailScroll = 0;
    });
    load();
  }

  void back() {
    setState(() {
      item = null;
      detail = null;
    });
    load();
  }

  @override
  void dispose() {
    generation++;
    scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final actions = (partner?['actions'] as List? ?? []).cast<String>();
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxHeight < 420;
        return Padding(
          padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(28),
            child: Material(
              color: const Color(0xfffafbff),
              child: Column(
                children: [
                  Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        colors: [Color(0xff202642), Color(0xff11172c)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                    padding: EdgeInsets.fromLTRB(
                      18,
                      compact ? 6 : 16,
                      10,
                      compact ? 8 : 14,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Container(
                              width: 34,
                              height: 34,
                              padding: const EdgeInsets.all(6),
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Image.asset('assets/chimera_logo.png'),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    'CHIMERA ASSIST',
                                    style: theme.textTheme.labelSmall?.copyWith(
                                      color: const Color(0xffbac2e7),
                                      letterSpacing: 1.6,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  const SizedBox(height: 3),
                                  Text(
                                    partner == null
                                        ? t('La îndemână', 'At your fingertips')
                                        : (partner!['kind'] == 'supplier'
                                              ? t('Furnizor', 'Supplier')
                                              : t('Client', 'Client')),
                                    style: const TextStyle(
                                      color: Color(0xffcbd2ef),
                                      fontSize: 12,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            if (widget.partners.length > 1 && partner != null)
                              IconButton(
                                tooltip: t(
                                  'Schimbă partenerul',
                                  'Change partner',
                                ),
                                onPressed: () {
                                  generation++;
                                  setState(() {
                                    partner = null;
                                    action = item = null;
                                    rows = detail = null;
                                    loading = false;
                                    error = null;
                                    offset = detailOffset = 0;
                                    listScroll = detailScroll = 0;
                                  });
                                  resize();
                                },
                                icon: const Icon(
                                  Icons.people_outline,
                                  color: Colors.white,
                                ),
                              ),
                            if (widget.floating)
                              IconButton(
                                tooltip: t('Minimizează', 'Minimize'),
                                onPressed: () => widget.bridge.call('minimize'),
                                icon: const Icon(
                                  Icons.keyboard_arrow_down_rounded,
                                  color: Colors.white,
                                ),
                              ),
                          ],
                        ),
                        SizedBox(height: compact ? 4 : 14),
                        Text(
                          partner?['name'] as String? ??
                              (widget.demo
                                  ? t(
                                      'Panoul tău pentru apeluri',
                                      'Your call workspace',
                                    )
                                  : t(
                                      'Alege partenerul',
                                      'Choose the partner',
                                    )),
                          maxLines: compact ? 1 : 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: compact ? 16 : 20,
                            fontWeight: FontWeight.w600,
                            height: 1.2,
                          ),
                        ),
                        if (actions.isNotEmpty) ...[
                          SizedBox(height: compact ? 6 : 16),
                          Row(
                            children: actions
                                .map(
                                  (a) => Expanded(
                                    child: Padding(
                                      padding: const EdgeInsets.only(right: 6),
                                      child: Semantics(
                                        selected: a == action,
                                        child: TextButton(
                                          style: TextButton.styleFrom(
                                            foregroundColor: a == action
                                                ? const Color(0xff262151)
                                                : const Color(0xffd6dcf5),
                                            backgroundColor: a == action
                                                ? const Color(0xffdcd6ff)
                                                : const Color(0xff2c3350),
                                            padding: EdgeInsets.symmetric(
                                              vertical: compact ? 6 : 11,
                                              horizontal: 4,
                                            ),
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(13),
                                            ),
                                          ),
                                          onPressed: () => choose(a),
                                          child: Column(
                                            mainAxisSize: MainAxisSize.min,
                                            children: [
                                              if (!compact)
                                                Icon(switch (a) {
                                                  'invoices' =>
                                                    Icons.receipt_long_outlined,
                                                  'payments' =>
                                                    Icons
                                                        .account_balance_wallet_outlined,
                                                  _ =>
                                                    Icons.inventory_2_outlined,
                                                }, size: 19),
                                              if (!compact)
                                                const SizedBox(height: 5),
                                              Text(
                                                label(a),
                                                style: const TextStyle(
                                                  fontSize: 12,
                                                  fontWeight: FontWeight.w600,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                )
                                .toList(),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (loading) const LinearProgressIndicator(minHeight: 2),
                  Expanded(child: body()),
                  if (action != null && error == null) pager(),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget notice(IconData icon, String text, {bool retry = false}) => Center(
    child: SingleChildScrollView(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 30, color: const Color(0xff7d7a9b)),
            const SizedBox(height: 12),
            Text(
              text,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Color(0xff66637f), height: 1.5),
            ),
            if (retry)
              TextButton.icon(
                onPressed: load,
                icon: const Icon(Icons.refresh_rounded),
                label: Text(t('Reîncearcă', 'Retry')),
              ),
          ],
        ),
      ),
    ),
  );
  Widget body() {
    if (widget.demo) {
      return notice(
        Icons.touch_app_outlined,
        t(
          'Atinge în afară pentru a minimiza. Cercul păstrează locul unde ai rămas.',
          'Tap outside to minimize. The circle remembers where you left off.',
        ),
      );
    }
    if (widget.partners.isEmpty) {
      return notice(
        Icons.phone_in_talk_outlined,
        t('Caut contextul apelului…', 'Finding the call context…'),
      );
    }
    if (partner == null) {
      return ListView(
        children: widget.partners
            .map(
              (p) => ListTile(
                title: Text(p['name'] as String),
                subtitle: Text(
                  p['kind'] == 'supplier'
                      ? t('Furnizor', 'Supplier')
                      : t('Client', 'Client'),
                ),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () {
                  setState(() {
                    partner = p;
                  });
                  remember();
                  resize();
                },
              ),
            )
            .toList(),
      );
    }
    if (action == null) {
      return notice(
        Icons.north_rounded,
        t(
          'Alege o acțiune din antet. Totul rămâne aici, lângă apel.',
          'Choose an action above. Everything stays here, beside your call.',
        ),
      );
    }
    if (error != null) {
      return notice(Icons.cloud_off_outlined, switch (error) {
        'forbidden' => t(
          'Nu mai ai acces la aceste date.',
          'You no longer have access to these records.',
        ),
        'not_found' => t(
          'Înregistrarea nu mai este disponibilă.',
          'This record is no longer available.',
        ),
        'session_expired' => t(
          'Autentifică-te din nou în Assist.',
          'Sign in again in Assist.',
        ),
        _ => t(
          'Datele nu au putut fi încărcate.',
          'Could not load these records.',
        ),
      }, retry: true);
    }
    if (loading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }
    if (item != null) return detailBody();
    final values = objects(rows?['rows']);
    if (values.isEmpty) {
      return notice(
        Icons.inbox_outlined,
        t('Nicio înregistrare.', 'No records.'),
      );
    }
    return ListView.separated(
      controller: scroll,
      padding: const EdgeInsets.symmetric(vertical: 6),
      itemCount: values.length,
      separatorBuilder: (_, _) => const Divider(
        height: 1,
        indent: 18,
        endIndent: 18,
        color: Color(0xffe9eaf2),
      ),
      itemBuilder: (context, index) {
        final row = values[index];
        return InkWell(
          onTap: () => open(row),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        row['label'] as String,
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 14,
                          color: Color(0xff272b44),
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        row['detail'] as String? ?? '',
                        style: const TextStyle(
                          fontSize: 11,
                          color: Color(0xff797d93),
                        ),
                      ),
                      if (row['outstanding'] != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text(
                            '${label('outstanding')}: ${row['outstanding']} ${row['currency'] ?? ''}',
                            style: const TextStyle(
                              fontSize: 11,
                              color: Color(0xff79609e),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                if (row['amount'] != null)
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(
                          row['amount'] as String,
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 14,
                          ),
                        ),
                        Text(
                          row['currency'] as String? ?? '',
                          style: const TextStyle(
                            fontSize: 10,
                            color: Color(0xff797d93),
                          ),
                        ),
                      ],
                    ),
                  ),
                const SizedBox(width: 6),
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: Color(0xff9698ac),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget fields(Object? value) => Column(
    children: objects(value)
        .map(
          (field) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 7),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  flex: 4,
                  child: Text(
                    label(field['key'] as String),
                    style: const TextStyle(
                      color: Color(0xff74788e),
                      fontSize: 12,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  flex: 5,
                  child: Text(
                    field['value'] as String,
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontSize: 13,
                      color: const Color(0xff272b44),
                      fontWeight:
                          [
                            'total',
                            'amount',
                            'outstanding',
                          ].contains(field['key'])
                          ? FontWeight.w700
                          : FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
          ),
        )
        .toList(),
  );
  Widget detailBody() => Column(
    children: [
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          onPressed: back,
          icon: const Icon(Icons.arrow_back_rounded, size: 17),
          label: Text(t('Înapoi la listă', 'Back to list')),
        ),
      ),
      Expanded(
        child: ListView(
          controller: scroll,
          padding: const EdgeInsets.fromLTRB(18, 0, 18, 14),
          children: [
            Text(
              detail?['title'] as String? ?? '',
              style: const TextStyle(
                fontSize: 19,
                fontWeight: FontWeight.w600,
                color: Color(0xff272b44),
              ),
            ),
            const SizedBox(height: 10),
            fields(detail?['fields']),
            if (objects(detail?['lines']).isNotEmpty) ...[
              const Divider(height: 28),
              Text(
                t('POZIȚII FACTURĂ', 'INVOICE LINES'),
                style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.5,
                  color: Color(0xff87829b),
                ),
              ),
              ...objects(detail?['lines']).map(
                (line) => Padding(
                  padding: const EdgeInsets.only(top: 17),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        line['label'] as String,
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      fields(line['fields']),
                      const Divider(height: 12),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    ],
  );
  Widget pager() {
    if (item != null && action != 'invoices') {
      return Align(
        alignment: Alignment.centerRight,
        child: TextButton.icon(
          onPressed: loading ? null : load,
          icon: const Icon(Icons.refresh_rounded, size: 17),
          label: Text(t('Reîncarcă', 'Refresh')),
        ),
      );
    }
    final start = item == null ? offset : detailOffset;
    final more = (item == null ? rows : detail)?['has_more'] == true;
    return Container(
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Color(0xffe7e8f0))),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
      child: Row(
        children: [
          IconButton(
            tooltip: t('Pagina anterioară', 'Previous page'),
            onPressed: !loading && start > 0
                ? () => changePage(start - 25)
                : null,
            icon: const Icon(Icons.chevron_left_rounded),
          ),
          Expanded(
            child: Text(
              '${t('Pagina', 'Page')} ${start ~/ 25 + 1}',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: Color(0xff74788e)),
            ),
          ),
          IconButton(
            tooltip: t('Reîncarcă', 'Refresh'),
            onPressed: loading ? null : load,
            icon: const Icon(Icons.refresh_rounded, size: 19),
          ),
          IconButton(
            tooltip: t('Pagina următoare', 'Next page'),
            onPressed: !loading && more && start < 10000
                ? () => changePage(start + 25)
                : null,
            icon: const Icon(Icons.chevron_right_rounded),
          ),
        ],
      ),
    );
  }

  void changePage(int value) {
    setState(() {
      if (item == null) {
        offset = value;
        listScroll = 0;
      } else {
        detailOffset = value;
        detailScroll = 0;
      }
    });
    load();
  }
}

/// Debug-only browser harness: the rendered panel and requests above are unchanged.
class WorkspacePreview extends StatefulWidget {
  final AssistBridge bridge;
  final bool? romanian;
  const WorkspacePreview({super.key, required this.bridge, this.romanian});
  @override
  State<WorkspacePreview> createState() => _WorkspacePreviewState();
}

class _WorkspacePreviewState extends State<WorkspacePreview> {
  double height = 310;
  bool minimized = false;
  late final AssistBridge bridge = _PreviewPanelBridge(widget.bridge, (
    method,
    args,
  ) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        setState(() {
          if (method == 'minimize') minimized = true;
          if (method == 'resize') height = (args['height'] as num).toDouble();
        });
      }
    });
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xffdde4ec),
    body: Stack(
      children: [
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() => minimized = true),
            child: const Column(
              children: [
                SizedBox(height: 52),
                Icon(
                  Icons.phone_in_talk_rounded,
                  size: 32,
                  color: Color(0xff7c8795),
                ),
                SizedBox(height: 10),
                Text(
                  'Apel demonstrativ',
                  style: TextStyle(color: Color(0xff647181)),
                ),
              ],
            ),
          ),
        ),
        Positioned(
          top: 140,
          right: 8,
          left: 8,
          child: Offstage(
            offstage: minimized,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              curve: Curves.easeOutCubic,
              height: height.clamp(
                160,
                MediaQuery.sizeOf(context).height - 164,
              ),
              child: CallerWorkspace(bridge: bridge, romanian: widget.romanian),
            ),
          ),
        ),
        if (minimized)
          Positioned(
            right: 14,
            top: MediaQuery.sizeOf(context).height / 2,
            child: FloatingActionButton(
              tooltip: 'Chimera Assist',
              backgroundColor: Colors.white,
              onPressed: () => setState(() => minimized = false),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Image.asset('assets/chimera_logo.png'),
              ),
            ),
          ),
      ],
    ),
  );
}

class _PreviewPanelBridge implements AssistBridge {
  final AssistBridge delegate;
  final void Function(String, Json) event;
  _PreviewPanelBridge(this.delegate, this.event);
  @override
  Future<Object?> call(String method, [Json arguments = const {}]) {
    if (method == 'resize' || method == 'minimize') event(method, arguments);
    return delegate.call(method, arguments);
  }
}
