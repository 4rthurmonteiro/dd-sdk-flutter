// Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
// This product includes software developed at Datadog (https://www.datadoghq.com/).
// Copyright 2019-2022 Datadog, Inc.

import 'dart:io';

import 'package:datadog_common_test/datadog_common_test.dart';
import 'package:datadog_flutter_plugin/datadog_flutter_plugin.dart';
import 'package:datadog_flutter_plugin/datadog_internal.dart';
import 'package:datadog_flutter_plugin/src/rum/ddrum_noop_platform.dart';
import 'package:datadog_flutter_plugin/src/rum/ddrum_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class MockInternalLogger extends Mock implements InternalLogger {}

class MockDatadogSdk extends Mock implements DatadogSdk {}

class MockDatadogPlatform extends Mock implements DatadogSdkPlatform {}

void main() {
  const numSamples = 500;
  late MockInternalLogger mockInternalLogger;
  late MockDatadogSdk mockDatadogSdk;
  late MockDatadogPlatform mockDatadogPlatform;

  setUp(() {
    mockInternalLogger = MockInternalLogger();
    DdRumPlatform.instance = DdNoOpRumPlatform();

    mockDatadogSdk = MockDatadogSdk();
    when(() => mockDatadogSdk.internalLogger).thenReturn(mockInternalLogger);

    mockDatadogPlatform = MockDatadogPlatform();
    when(() => mockDatadogPlatform.updateTelemetryConfiguration(any(), any()))
        .thenAnswer((_) => Future.value());

    when(() => mockDatadogSdk.platform).thenReturn(mockDatadogPlatform);
  });

  test('RumResourceType parses simple mimeTypes from ContentType', () {
    final image = ContentType.parse('image/png');
    expect(resourceTypeFromContentType(image), RumResourceType.image);

    final video = ContentType.parse('video/mp4');
    expect(resourceTypeFromContentType(video), RumResourceType.media);

    final audio = ContentType.parse('audio/ogg');
    expect(resourceTypeFromContentType(audio), RumResourceType.media);

    final appJavascript = ContentType.parse('application/javascript');
    expect(resourceTypeFromContentType(appJavascript), RumResourceType.js);

    final textJavascript = ContentType.parse('text/javascript');
    expect(resourceTypeFromContentType(textJavascript), RumResourceType.js);

    final font = ContentType.parse('font/collection');
    expect(resourceTypeFromContentType(font), RumResourceType.font);

    final css = ContentType.parse('text/css');
    expect(resourceTypeFromContentType(css), RumResourceType.css);

    final other = ContentType.parse('application/octet-stream');
    expect(resourceTypeFromContentType(other), RumResourceType.native);
  });

  test('configuration is encoded correctly', () {
    final applicationId = randomString();
    final detectLongTasks = randomBool();
    final trackFrustrations = randomBool();
    final vitalUpdateFrequency = VitalsFrequency.values.randomElement();
    final customEndpoint = randomString();
    final configuration = DatadogRumConfiguration(
      applicationId: applicationId,
      sessionSamplingRate: 12.0,
      tracingSamplingRate: 50.2,
      detectLongTasks: detectLongTasks,
      longTaskThreshold: 0.3,
      trackFrustrations: trackFrustrations,
      vitalUpdateFrequency: vitalUpdateFrequency,
      customEndpoint: customEndpoint,
    );

    final encoded = configuration.encode();
    expect(encoded['applicationId'], applicationId);
    expect(encoded['sessionSampleRate'], 12.0);
    expect(encoded['detectLongTasks'], detectLongTasks);
    expect(encoded['longTaskThreshold'], 0.3);
    expect(encoded['trackFrustrations'], trackFrustrations);
    expect(encoded['vitalsUpdateFrequency'], vitalUpdateFrequency.toString());
    expect(encoded['customEndpoint'], customEndpoint);
  });

  test('configuration with mapper sets attach*Mapper', () {
    final configuration = DatadogRumConfiguration(
      applicationId: 'fake-application-id',
      viewEventMapper: (event) => event,
      actionEventMapper: (event) => event,
      resourceEventMapper: (event) => event,
      errorEventMapper: (event) => event,
      longTaskEventMapper: (event) => event,
    );

    final encoded = configuration.encode();
    expect(encoded['attachViewEventMapper'], isTrue);
    expect(encoded['attachActionEventMapper'], isTrue);
    expect(encoded['attachResourceEventMapper'], isTrue);
    expect(encoded['attachErrorEventMapper'], isTrue);
    expect(encoded['attachLongTaskEventMapper'], isTrue);
  });

  test('Session sampling rate is clamped to 0..100', () {
    final lowConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      sessionSamplingRate: -12.3,
    );

    final highConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      sessionSamplingRate: 137.2,
    );

    expect(lowConfiguration.sessionSamplingRate, equals(0.0));
    expect(highConfiguration.sessionSamplingRate, equals(100.0));
  });

  test('Tracing sampling rate is clamped to 0..100', () {
    final lowConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: -12.3,
    );

    final highConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: 137.2,
    );

    expect(lowConfiguration.tracingSamplingRate, equals(0.0));
    expect(highConfiguration.tracingSamplingRate, equals(100.0));
  });

  test('Setting trace sample rate to 100 should always sample', () async {
    final rumConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: 100,
      detectLongTasks: false,
    );
    final rum = await DatadogRum.enable(mockDatadogSdk, rumConfiguration);

    for (int i = 0; i < 10; ++i) {
      expect(rum!.shouldSampleTrace(), isTrue);
    }
  });

  test('Setting trace sample rate to 0 should never sample', () async {
    final rumConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: 0,
      detectLongTasks: false,
    );
    final rum = await DatadogRum.enable(mockDatadogSdk, rumConfiguration);

    for (int i = 0; i < 10; ++i) {
      expect(rum!.shouldSampleTrace(), isFalse);
    }
  });

  test('Low sampling rate returns samples less often', () async {
    final rumConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: 23,
      detectLongTasks: false,
    );
    final rum = await DatadogRum.enable(mockDatadogSdk, rumConfiguration);

    var sampleCount = 0;
    var noSampleCount = 0;
    for (int i = 0; i < numSamples; ++i) {
      if (rum!.shouldSampleTrace()) {
        sampleCount++;
      } else {
        noSampleCount++;
      }
    }

    expect(noSampleCount, greaterThanOrEqualTo(sampleCount));
    expect(sampleCount, greaterThanOrEqualTo(1));
  });

  test('High sampling rate returns samples more often', () async {
    final rumConfiguration = DatadogRumConfiguration(
      applicationId: 'applicationId',
      tracingSamplingRate: 85,
      detectLongTasks: false,
    );
    final rum = await DatadogRum.enable(mockDatadogSdk, rumConfiguration);

    var sampleCount = 0;
    var noSampleCount = 0;
    for (int i = 0; i < numSamples; ++i) {
      if (rum!.shouldSampleTrace()) {
        sampleCount++;
      } else {
        noSampleCount++;
      }
    }

    expect(sampleCount, greaterThanOrEqualTo(noSampleCount));
    expect(noSampleCount, greaterThanOrEqualTo(1));
  });
}
