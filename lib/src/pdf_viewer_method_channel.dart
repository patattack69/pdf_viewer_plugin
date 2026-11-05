import 'dart:async';

import 'package:flutter/services.dart';

import '../pdf_viewer_plugin.dart';

class MethodChannelPdfViewerPlatform {
  /// Constructs an instance that will listen for pdf views broadcasting to the
  /// given [id]
  MethodChannelPdfViewerPlatform(int id)
      : _channel = MethodChannel('pdf_viewer_plugin_$id') {
    _channel.setMethodCallHandler(_onMethodCall);
  }

  final MethodChannel _channel;

  /// TEMPORARY: native code pushes the whole diagnostics list on every change. Pushes sent before
  /// this handler is registered may be dropped, so the current list is also pulled once.
  static void listenDiagnostics(int id, PdfViewDiagnosticsCallback? onDiagnostics) {
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

  Future<bool> _onMethodCall(MethodCall call) async {
    throw MissingPluginException(
      '${call.method} was invoked but has no handler',
    );
  }

  /// Converts a [CreationParams] object to a map as expected by `platform_views` channel.
  ///
  /// This is used for the `creationParams` argument of the platform views created by
  /// [AndroidPdfViewerBuilder] and [CupertinoPdfViewerBuilder].
  static Map<String, dynamic> creationParamsToMap(
      CreationParams creationParams) {
    return <String, dynamic>{
      'filePath': creationParams.path,
    };
  }
}
