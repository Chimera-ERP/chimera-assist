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
        .call('resize', {'height': requestedHeight})
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

  final surfaceKey = GlobalKey(), anchorKey = GlobalKey();
  void outside(PointerDownEvent event) {
    if (!widget.floating) return;
    bool contains(GlobalKey key) {
      final box = key.currentContext?.findRenderObject();
      return box is RenderBox &&
          box.hasSize &&
          (box.localToGlobal(Offset.zero) & box.size).contains(event.position);
    }

    if (!contains(surfaceKey) && !contains(anchorKey)) {
      widget.bridge.call('minimize');
    }
  }

  double textScale = 1;
  int get requestedHeight => action != null
      ? 460
      : partner == null || widget.demo || widget.partners.isEmpty
      ? 300
      : (260 + (textScale - 1).clamp(0, 1.5) * 140).round();

  IconData actionIcon(String value) => switch (value) {
    'invoices' => Icons.receipt_long_outlined,
    'payments' => Icons.account_balance_wallet_outlined,
    _ => Icons.inventory_2_outlined,
  };

  @override
  Widget build(BuildContext context) {
    final scale = MediaQuery.textScalerOf(context).scale(14) / 14;
    if (scale != textScale) {
      textScale = scale;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) resize();
      });
    }
    final actions = (partner?['actions'] as List? ?? []).cast<String>();
    final showBody =
        action != null || partner == null || widget.demo || actions.isEmpty;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    return Listener(
      onPointerDown: outside,
      behavior: HitTestBehavior.translucent,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final compact = constraints.maxHeight < 320 && action != null;
          final showAnchor =
              widget.floating &&
              !compact &&
              constraints.maxHeight >= requestedHeight;
          final limit = requestedHeight
              .toDouble()
              .clamp(0.0, constraints.maxHeight)
              .toDouble();
          return Align(
            alignment: Alignment.topRight,
            child: SizedBox(
              height: limit,
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Column(
                  children: [
                    if (showAnchor) ...[
                      Align(
                        alignment: Alignment.centerRight,
                        child: IconButton.filledTonal(
                          key: anchorKey,
                          tooltip: t('Minimizează', 'Minimize'),
                          style: IconButton.styleFrom(
                            backgroundColor: Colors.white,
                            fixedSize: const Size(48, 48),
                            shape: const CircleBorder(),
                          ),
                          onPressed: () => widget.bridge.call('minimize'),
                          icon: Image.asset(
                            'assets/chimera_logo.png',
                            width: 30,
                            height: 30,
                          ),
                        ),
                      ),
                      const SizedBox(height: 8),
                    ],
                    Flexible(
                      fit: showBody ? FlexFit.tight : FlexFit.loose,
                      child: Material(
                        key: surfaceKey,
                        color: const Color(0xfffcfcff),
                        elevation: 5,
                        shadowColor: const Color(0x33202642),
                        borderRadius: BorderRadius.circular(26),
                        clipBehavior: Clip.antiAlias,
                        child: panelContents(showBody, [
                          Padding(
                            padding: EdgeInsets.fromLTRB(
                              16,
                              compact ? 4 : 12,
                              8,
                              compact ? 2 : 8,
                            ),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      if (!compact)
                                        Text(
                                          partner == null
                                              ? 'Chimera Assist'
                                              : partner!['kind'] == 'supplier'
                                              ? t('Furnizor', 'Supplier')
                                              : t('Client', 'Client'),
                                          style: const TextStyle(
                                            color: Color(0xff66637f),
                                            fontSize: 12,
                                          ),
                                        ),
                                      Text(
                                        partner?['name'] as String? ??
                                            (widget.demo
                                                ? t(
                                                    'Testează cercul',
                                                    'Test the circle',
                                                  )
                                                : t(
                                                    'Alege partenerul',
                                                    'Choose the partner',
                                                  )),
                                        maxLines: compact ? 1 : 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          color: Color(0xff202642),
                                          fontSize: 16,
                                          fontWeight: FontWeight.w600,
                                          height: 1.2,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                if (widget.partners.length > 1 &&
                                    partner != null)
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
                                      size: 20,
                                    ),
                                  ),
                                if (widget.floating && !showAnchor)
                                  IconButton(
                                    tooltip: t('Minimizează', 'Minimize'),
                                    onPressed: () =>
                                        widget.bridge.call('minimize'),
                                    icon: const Icon(
                                      Icons.keyboard_arrow_down_rounded,
                                    ),
                                  ),
                              ],
                            ),
                          ),
                          if (actions.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                              ),
                              child: Row(
                                children: [
                                  for (final a in actions)
                                    Expanded(
                                      child: TweenAnimationBuilder<double>(
                                        tween: Tween(
                                          begin: reducedMotion ? 1 : 0,
                                          end: 1,
                                        ),
                                        duration: reducedMotion
                                            ? Duration.zero
                                            : const Duration(milliseconds: 220),
                                        curve: Curves.easeOutCubic,
                                        builder: (context, value, child) =>
                                            Transform.translate(
                                              offset: Offset(
                                                (1 - value) *
                                                    (actions.length -
                                                        actions.indexOf(a)) *
                                                    18,
                                                (1 - value) * -20,
                                              ),
                                              child: Opacity(
                                                opacity: value,
                                                child: child,
                                              ),
                                            ),
                                        child: Semantics(
                                          selected: action == a,
                                          child: TextButton(
                                            onPressed: () => choose(a),
                                            style: TextButton.styleFrom(
                                              minimumSize: const Size(48, 48),
                                              foregroundColor: action == a
                                                  ? const Color(0xff4434b5)
                                                  : const Color(0xff343b53),
                                              backgroundColor: action == a
                                                  ? const Color(0xffeeebff)
                                                  : Colors.transparent,
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                    horizontal: 4,
                                                    vertical: 8,
                                                  ),
                                              shape: RoundedRectangleBorder(
                                                borderRadius:
                                                    BorderRadius.circular(18),
                                              ),
                                            ),
                                            child: action == null
                                                ? Column(
                                                    mainAxisSize:
                                                        MainAxisSize.min,
                                                    children: [
                                                      Container(
                                                        width: 44,
                                                        height: 44,
                                                        decoration:
                                                            const BoxDecoration(
                                                              color: Color(
                                                                0xffeeebff,
                                                              ),
                                                              shape: BoxShape
                                                                  .circle,
                                                            ),
                                                        child: Icon(
                                                          actionIcon(a),
                                                          size: 22,
                                                          color: const Color(
                                                            0xff5144d3,
                                                          ),
                                                        ),
                                                      ),
                                                      const SizedBox(height: 6),
                                                      Text(
                                                        label(a),
                                                        style: const TextStyle(
                                                          fontSize: 13,
                                                          fontWeight:
                                                              FontWeight.w600,
                                                        ),
                                                      ),
                                                    ],
                                                  )
                                                : Text(
                                                    label(a),
                                                    style: const TextStyle(
                                                      fontSize: 12,
                                                      fontWeight:
                                                          FontWeight.w600,
                                                    ),
                                                  ),
                                          ),
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          if (!showBody) const SizedBox(height: 8),
                          if (showBody) ...[
                            const SizedBox(height: 4),
                            if (loading)
                              const LinearProgressIndicator(minHeight: 2),
                            Expanded(child: body()),
                            if (action != null && error == null) pager(),
                          ],
                        ]),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget panelContents(bool hasBody, List<Widget> children) => hasBody
      ? Column(children: children)
      : SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: children),
        );

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
        Icons.lock_outline,
        t(
          'Nu ai acces la documentele acestui partener.',
          'No document actions available for this partner.',
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
        'session_upgrade_required' => t(
          'Conectează-te din nou în Assist pentru a activa reînnoirea sesiunii.',
          'Sign in again in Assist to enable session renewal.',
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
                          color: Color(0xff626980),
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
                            color: Color(0xff626980),
                          ),
                        ),
                      ],
                    ),
                  ),
                const SizedBox(width: 6),
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: Color(0xff727891),
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
                      color: Color(0xff626980),
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
              style: const TextStyle(fontSize: 12, color: Color(0xff626980)),
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
  double height = 260;
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
          right: 20,
          width: (MediaQuery.sizeOf(context).width - 48).clamp(160, 336),
          child: Offstage(
            offstage: minimized,
            child: SizedBox(
              height: height.clamp(
                160,
                (MediaQuery.sizeOf(context).height * .62).clamp(160, 480),
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
