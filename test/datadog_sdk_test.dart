// Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
// This product includes software developed at Datadog (https://www.datadoghq.com/).
// Copyright 2019-2020 Datadog, Inc.

import 'package:datadog_sdk/src/datadog_sdk_platform_interface.dart';
import 'package:datadog_sdk/datadog_sdk.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockDatadogSdkPlatform extends Mock
    with MockPlatformInterfaceMixin
    implements DatadogSdkPlatform {}

class FakeDdSdkConfiguration extends Fake implements DdSdkConfiguration {}

void main() {
  late DatadogSdk datadogSdk;
  late MockDatadogSdkPlatform mockPlatform;

  setUpAll(() {
    registerFallbackValue(FakeDdSdkConfiguration());
  });

  setUp(() {
    mockPlatform = MockDatadogSdkPlatform();
    when(() => mockPlatform.initialize(any(),
            logCallback: any(named: 'logCallback')))
        .thenAnswer((_) => Future.value());
    when(() => mockPlatform.setUserInfo(any(), any(), any(), any()))
        .thenAnswer((_) => Future.value());
    DatadogSdkPlatform.instance = mockPlatform;
    datadogSdk = DatadogSdk.instance;
  });

  test('initialize passes configuration to platform', () async {
    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
    );
    await datadogSdk.initialize(configuration);

    verify(() => mockPlatform.initialize(configuration,
        logCallback: any(named: 'logCallback')));
  });

  test('encode base configuration', () {
    final configuration = DdSdkConfiguration(
      clientToken: 'fake-client-token',
      env: 'prod',
      trackingConsent: TrackingConsent.pending,
    );
    final encoded = configuration.encode();
    expect(encoded, {
      'clientToken': 'fake-client-token',
      'env': 'prod',
      'site': null,
      'nativeCrashReportEnabled': false,
      'trackingConsent': 'TrackingConsent.pending',
      'customEndpoint': null,
      'batchSize': null,
      'uploadFrequency': null,
      'loggingConfiguration': null,
      'tracingConfiguration': null,
      'rumConfiguration': null,
      'additionalConfig': {},
    });
  });

  test('initialize encoding serializes enums correctly', () {
    final configuration = DdSdkConfiguration(
      clientToken: 'fakeClientToken',
      env: 'environment',
      trackingConsent: TrackingConsent.granted,
    )
      ..batchSize = BatchSize.small
      ..uploadFrequency = UploadFrequency.frequent
      ..site = DatadogSite.eu1;

    final encoded = configuration.encode();
    expect(encoded['batchSize'], 'BatchSize.small');
    expect(encoded['uploadFrequency'], 'UploadFrequency.frequent');
    expect(encoded['site'], 'DatadogSite.eu1');
  });

  test('configuration encodes default sub-configuration', () {
    final configuration = DdSdkConfiguration(
      clientToken: 'fakeClientToken',
      env: 'fake-env',
      trackingConsent: TrackingConsent.notGranted,
      loggingConfiguration: LoggingConfiguration(),
      tracingConfiguration: TracingConfiguration(),
      rumConfiguration: RumConfiguration(applicationId: 'fake-application-id'),
    );

    final encoded = configuration.encode();
    expect(encoded['loggingConfiguration'],
        configuration.loggingConfiguration?.encode());
    expect(encoded['tracingConfiguration'],
        configuration.tracingConfiguration?.encode());
    expect(
        encoded['rumConfiguration'], configuration.rumConfiguration?.encode());
  });

  test('first party hosts get set to sdk', () async {
    var firstPartyHosts = ['example.com', 'datadoghq.com'];

    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
      firstPartyHosts: firstPartyHosts,
    );
    await datadogSdk.initialize(configuration);

    expect(datadogSdk.firstPartyHosts, firstPartyHosts);
  });

  test('isFirstPartyHost with no hosts returns false', () async {
    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
    );
    await datadogSdk.initialize(configuration);

    var uri = Uri.parse('https://first_party');
    expect(datadogSdk.isFirstPartyHost(uri), isFalse);
  });

  test('isFirstPartyHost with matching host returns true', () async {
    var firstPartyHosts = ['example.com', 'datadoghq.com'];

    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
      firstPartyHosts: firstPartyHosts,
    );
    await datadogSdk.initialize(configuration);

    var uri = Uri.parse('https://datadoghq.com/path');
    expect(datadogSdk.isFirstPartyHost(uri), isTrue);
  });

  test('isFirstPartyHost with matching host with subdomain returns true',
      () async {
    var firstPartyHosts = ['example.com', 'datadoghq.com'];

    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
      firstPartyHosts: firstPartyHosts,
    );
    await datadogSdk.initialize(configuration);

    var uri = Uri.parse('https://test.datadoghq.com/path');
    expect(datadogSdk.isFirstPartyHost(uri), isTrue);
  });

  test('isFirstPartyHost with matching subdomain does not match root',
      () async {
    var firstPartyHosts = ['example.com', 'test.datadoghq.com'];

    final configuration = DdSdkConfiguration(
      clientToken: 'clientToken',
      env: 'env',
      trackingConsent: TrackingConsent.pending,
      firstPartyHosts: firstPartyHosts,
    );
    await datadogSdk.initialize(configuration);

    var uri = Uri.parse('https://datadoghq.com/path');
    expect(datadogSdk.isFirstPartyHost(uri), isFalse);
  });

  test('set user info calls into platform', () {
    datadogSdk.setUserInfo(
        id: 'fake_id', name: 'fake_name', email: 'fake_email');

    verify(() =>
        mockPlatform.setUserInfo('fake_id', 'fake_name', 'fake_email', {}));
  });

  test('set user info calls into platform passing extraInfo', () {
    datadogSdk.setUserInfo(
      id: 'fake_id',
      name: 'fake_name',
      email: 'fake_email',
      extraInfo: {'attribute': 32.0},
    );

    verify(() => mockPlatform.setUserInfo(
          'fake_id',
          'fake_name',
          'fake_email',
          {'attribute': 32.0},
        ));
  });

  test('set user info calls into platform passing null values', () {
    datadogSdk.setUserInfo(id: null, name: null, email: null);

    verify(() => mockPlatform.setUserInfo(null, null, null, {}));
  });
}
