#import "PdfView.h"
#import <PDFKit/PDFKit.h>

@implementation PdfViewFactory {
  NSObject<FlutterBinaryMessenger>* _messenger;
}

- (instancetype)initWithMessenger:(NSObject<FlutterBinaryMessenger>*)messenger {
  self = [super init];
  if (self) {
    _messenger = messenger;
  }
  return self;
}

- (NSObject<FlutterMessageCodec>*)createArgsCodec {
  return [FlutterStandardMessageCodec sharedInstance];
}

- (NSObject<FlutterPlatformView>*)createWithFrame:(CGRect)frame
                                   viewIdentifier:(int64_t)viewId
                                        arguments:(id _Nullable)args {
  PdfViewController* webviewController = [[PdfViewController alloc] initWithFrame:frame
                                                                         viewIdentifier:viewId
                                                                              arguments:args
                                                                        binaryMessenger:_messenger];
  return webviewController;
}

@end

@implementation PdfViewController {
  WKWebView *_webView;
  int64_t _viewId;
  UIViewController *_viewController;
  FlutterMethodChannel* _channel;
  NSString* filePath;
  // TEMPORARY: load diagnostics, pulled by Dart via `getDiagnostics` and pushed via `diagnostics`.
  NSMutableArray<NSString*>* _diagnostics;
  CFAbsoluteTime _createdAt;
}

- (instancetype)initWithFrame:(CGRect)frame
               viewIdentifier:(int64_t)viewId
                    arguments:(id _Nullable)args
              binaryMessenger:(NSObject<FlutterBinaryMessenger>*)messenger {
    _viewId = viewId;
    _diagnostics = [NSMutableArray array];
    _createdAt = CFAbsoluteTimeGetCurrent();

    NSString* channelName = [NSString stringWithFormat:@"pdf_viewer_plugin_%lld", viewId];
    _channel = [FlutterMethodChannel methodChannelWithName:channelName binaryMessenger:messenger];
    __weak PdfViewController* weakSelf = self;
    [_channel setMethodCallHandler:^(FlutterMethodCall* call, FlutterResult result) {
        [weakSelf onMethodCall:call result:result];
    }];

    _viewController = [UIApplication sharedApplication].delegate.window.rootViewController;

    filePath = args[@"filePath"];

    if (_webView == nil){
        WKWebViewConfiguration* configuration = [[WKWebViewConfiguration alloc] init];
        _webView = [[WKWebView alloc] initWithFrame:frame configuration:configuration];
        _webView.navigationDelegate = self;
        NSURL *targetURL = [NSURL fileURLWithPath:filePath];
        [self logFile];
        [self logPdfKitDocument:targetURL];
        if (@available(iOS 9.0, *)) {
            WKNavigation* navigation = [_webView loadFileURL:targetURL allowingReadAccessToURL:targetURL];
            [self log:[NSString stringWithFormat:@"loadFileURL navigation=%@", navigation == nil ? @"nil" : @"ok"]];
        } else {
            NSURLRequest *request = [NSURLRequest requestWithURL:targetURL];
            [_webView loadRequest:request];
        }
    }

  return self;
}

- (void)dealloc {
    _webView.navigationDelegate = nil;
}

- (UIView*)view {
  return _webView;
}

- (void)onMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  if ([[call method] isEqualToString:@"updateSettings"]) {
    result(nil);
  } else if ([[call method] isEqualToString:@"getDiagnostics"]) {
    result([_diagnostics copy]);
  } else {
    result(FlutterMethodNotImplemented);
  }
}

#pragma mark - Diagnostics

- (void)log:(NSString*)message {
    int elapsedMs = (int)((CFAbsoluteTimeGetCurrent() - _createdAt) * 1000);
    [_diagnostics addObject:[NSString stringWithFormat:@"+%dms %@", elapsedMs, message]];
    // The whole list is sent every time: messages sent before Dart registers its handler may be dropped.
    [_channel invokeMethod:@"diagnostics" arguments:[_diagnostics copy]];
}

- (NSString*)describeError:(NSError*)error {
    return [NSString stringWithFormat:@"%@ code=%ld \"%@\"", error.domain, (long)error.code, error.localizedDescription];
}

- (void)logFile {
    NSError* error = nil;
    NSDictionary* attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:&error];
    if (attributes == nil) {
        [self log:[NSString stringWithFormat:@"native file: missing %@", [self describeError:error]]];
        return;
    }
    BOOL readable = [[NSFileManager defaultManager] isReadableFileAtPath:filePath];
    [self log:[NSString stringWithFormat:@"native file: size=%llu readable=%d ios=%@",
               [attributes fileSize], readable, [UIDevice currentDevice].systemVersion]];
}

- (void)logPdfKitDocument:(NSURL*)url {
    if (@available(iOS 11.0, *)) {
        PDFDocument* document = [[PDFDocument alloc] initWithURL:url];
        if (document == nil) {
            [self log:@"pdfkit: failed to open"];
            return;
        }
        NSString* firstPage = @"none";
        PDFPage* page = document.pageCount > 0 ? [document pageAtIndex:0] : nil;
        if (page != nil) {
            CGRect bounds = [page boundsForBox:kPDFDisplayBoxMediaBox];
            firstPage = [NSString stringWithFormat:@"%.0fx%.0f rot=%ld textLen=%lu",
                         bounds.size.width, bounds.size.height, (long)page.rotation, (unsigned long)page.string.length];
        }
        [self log:[NSString stringWithFormat:@"pdfkit: pages=%lu ver=%ld.%ld encrypted=%d locked=%d page1=%@",
                   (unsigned long)document.pageCount, (long)document.majorVersion, (long)document.minorVersion,
                   document.isEncrypted, document.isLocked, firstPage]];
    } else {
        [self log:@"pdfkit: unavailable"];
    }
}

- (void)logLayout:(NSString*)label {
    CGSize content = _webView.scrollView.contentSize;
    CGRect frame = _webView.frame;
    [self log:[NSString stringWithFormat:@"%@: frame=%.0fx%.0f content=%.0fx%.0f loading=%d progress=%.2f",
               label, frame.size.width, frame.size.height, content.width, content.height,
               _webView.isLoading, _webView.estimatedProgress]];
}

#pragma mark - WKNavigationDelegate

- (void)webView:(WKWebView*)webView didStartProvisionalNavigation:(WKNavigation*)navigation {
    [self log:@"nav: start"];
}

- (void)webView:(WKWebView*)webView
    decidePolicyForNavigationResponse:(WKNavigationResponse*)navigationResponse
                      decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    NSURLResponse* response = navigationResponse.response;
    [self log:[NSString stringWithFormat:@"nav: response mime=%@ length=%lld canShow=%d",
               response.MIMEType, response.expectedContentLength, navigationResponse.canShowMIMEType]];
    decisionHandler(WKNavigationResponsePolicyAllow);
}

- (void)webView:(WKWebView*)webView didCommitNavigation:(WKNavigation*)navigation {
    [self log:@"nav: commit"];
}

- (void)webView:(WKWebView*)webView didFinishNavigation:(WKNavigation*)navigation {
    [self logLayout:@"nav: finish"];
    __weak PdfViewController* weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [weakSelf logLayout:@"finish+2s"];
    });
}

- (void)webView:(WKWebView*)webView
    didFailProvisionalNavigation:(WKNavigation*)navigation
                       withError:(NSError*)error {
    [self log:[NSString stringWithFormat:@"nav: failProvisional %@", [self describeError:error]]];
}

- (void)webView:(WKWebView*)webView didFailNavigation:(WKNavigation*)navigation withError:(NSError*)error {
    [self log:[NSString stringWithFormat:@"nav: fail %@", [self describeError:error]]];
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView*)webView {
    [self log:@"nav: web content process terminated"];
}

@end
