import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:pdf_viewer_plugin/src/pdf_viewer_method_channel.dart';

import '../../pdf_viewer_plugin.dart';

class CupertinoPdfViewer implements PdfViewerPlatform {
  @override
  Widget build({
    BuildContext? context,
    CreationParams? creationParams,
    Set<Factory<OneSequenceGestureRecognizer>>? gestureRecognizers,
  }) {
    return UiKitView(
      viewType: 'pdf_viewer_plugin',
      onPlatformViewCreated: (int id) =>
          _listenDiagnostics(id, creationParams?.onDiagnostics),
      gestureRecognizers: gestureRecognizers,
      creationParams:
          MethodChannelPdfViewerPlatform.creationParamsToMap(creationParams!),
      creationParamsCodec: const StandardMessageCodec(),
    );
  }

  /// TEMPORARY: native code pushes the whole diagnostics list on every change. Pushes sent before
  /// this handler is registered may be dropped, so the current list is also pulled once.
  void _listenDiagnostics(int id, PdfViewDiagnosticsCallback? onDiagnostics) {
    if (onDiagnostics == null) return;
    final channel = MethodChannel('pdf_viewer_plugin_$id');
    var lastLength = -1;
    void deliver(List<String> diagnostics) {
      // A pulled list may arrive after a newer push; never go back to a shorter one.
      if (diagnostics.length < lastLength) return;
      lastLength = diagnostics.length;
      onDiagnostics(diagnostics);
    }

    channel.setMethodCallHandler((call) async {
      if (call.method == 'diagnostics') {
        deliver(List<String>.from(call.arguments as List));
      }
    });
    channel
        .invokeListMethod<String>('getDiagnostics')
        .then((diagnostics) => deliver(diagnostics ?? const []))
        .catchError((Object e) => deliver(['getDiagnostics failed: $e']));
  }
}
