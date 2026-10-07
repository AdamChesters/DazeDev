import { expect, it } from 'vitest';
import { releaseVersion } from '../src/lib/release-version';
import { releaseHeading } from '../src/lib/changelog';

it('shows the alpha base, increments and steps', () => {
  expect(releaseVersion('0.3.0-alpha.0')).toBe('Alpha-V0.3');
  expect(releaseVersion('0.3.1-alpha.0')).toBe('Alpha-V0.3.01');
  expect(releaseVersion('0.3.2-alpha.0')).toBe('Alpha-V0.3.02');
  expect(releaseVersion('0.4.0-alpha.0')).toBe('Alpha-V0.4');
});
it('shows stable bases, increments and steps', () => {
  expect(releaseVersion('1.0.0')).toBe('1.0');
  expect(releaseVersion('1.0.1')).toBe('1.0.01');
  expect(releaseVersion('1.0.2')).toBe('1.0.02');
  expect(releaseVersion('1.1.0')).toBe('1.1');
  expect(releaseVersion('1.1.1')).toBe('1.1.01');
  expect(releaseVersion('1.1.100')).toBe('1.1.100');
});
it('preserves historical and unfamiliar prerelease labels', () => {
  expect(releaseVersion('0.2.0-alpha.30')).toBe('0.2.0-alpha.30');
  expect(releaseVersion('0.2.0')).toBe('0.2.0');
  expect(releaseVersion('0.3.0-beta.1')).toBe('0.3.0-beta.1');
  expect(releaseVersion('unknown')).toBe('unknown');
});
it('uses the release label in dated changelog headings', () => {
  expect(releaseHeading('0.3.0-alpha.0', '2026-10-06')).toBe('6 October 2026 : Alpha-V0.3');
});
