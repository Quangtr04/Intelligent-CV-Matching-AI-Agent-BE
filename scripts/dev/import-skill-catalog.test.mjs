import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { convertRoadmap, readLocalSource } from './import-skill-catalog.mjs';

const review = () => ({ catalogVersion: 'synthetic-1', source: { id: 'synthetic', url: 'local:synthetic-fixture',
  revision: '1', license: 'Project-authored', permissionKind: 'USER_OWNED', permissionEvidence: 'Authored test fixture' },
  approvedNodes: {
    a: { skillId: 'alpha', name: 'Alpha', type: 'KNOWLEDGE', domain: 'TEST' },
    b: { skillId: 'beta', name: 'Beta', type: 'TOOL', domain: 'TEST' },
  }, prerequisiteEdges: [] });
const raw = () => ({ nodes: [{ id: 'a', type: 'topic', data: { label: 'Alpha' } },
  { id: 'b', type: 'topic', label: 'Beta' }, { id: 'title', type: 'title', label: 'Getting started' }],
  edges: [{ id: 'drawing', source: 'a', target: 'b' }] });

test('imports only reviewed nondecorative topics and preserves provenance deterministically', () => {
  const first = convertRoadmap(raw(), review(), 'abc123');
  assert.deepEqual(first, convertRoadmap(raw(), review(), 'abc123'));
  assert.deepEqual(first.catalog.skills.map(skill => skill.id), ['alpha', 'beta']);
  assert.equal(first.report.skippedNodes, 1);
  assert.equal(first.report.ignoredEdges, 1);
  assert.ok(first.catalog.sources.every(source => source.revision.includes('sha256:abc123')));
  assert.ok(first.catalog.topics.every(topic => topic.prerequisites.length === 0));
});

test('refuses roadmap.sh imports without written permission even if a manifest claims MIT', () => {
  const manifest = review();
  manifest.source.url = 'https://github.com/nilbuild/developer-roadmap';
  manifest.source.permissionKind = 'PERMISSIVE_LICENSE';
  manifest.source.license = 'MIT';
  assert.throws(() => convertRoadmap(raw(), manifest, 'hash'), /written permission/);
});

test('requires source revision, permission evidence, and explicit node decisions', () => {
  const manifest = review();
  delete manifest.source.permissionEvidence;
  assert.throws(() => convertRoadmap(raw(), manifest, 'hash'), /permission evidence/);
  const empty = review();
  empty.approvedNodes = {};
  assert.equal(convertRoadmap(raw(), empty, 'hash').catalog.skills.length, 0);
});

test('untagged diagram edges never become prerequisites, explicit reviewed source edges can', () => {
  const manifest = review();
  manifest.prerequisiteEdges = ['drawing'];
  assert.throws(() => convertRoadmap(raw(), manifest, 'hash'), /untyped connector/);
  const graph = raw();
  graph.edges[0].type = 'prerequisite';
  const imported = convertRoadmap(graph, manifest, 'hash');
  assert.deepEqual(imported.catalog.topics.find(topic => topic.id === 'beta-learning').prerequisites,
    ['alpha-learning']);
});

test('rejects duplicate source IDs, conflicting canonical identities, aliases and prerequisite cycles', () => {
  const graph = raw();
  graph.nodes.push({ ...graph.nodes[0] });
  assert.throws(() => convertRoadmap(graph, review(), 'hash'), /duplicate source node/);
  const aliases = review();
  aliases.approvedNodes.a.aliases = ['shared'];
  aliases.approvedNodes.b.aliases = ['shared'];
  assert.throws(() => convertRoadmap(raw(), aliases, 'hash'), /Conflicting alias/);
  const cyclic = raw();
  cyclic.edges = [{ id: 'ab', type: 'prerequisite', source: 'a', target: 'b' },
    { id: 'ba', type: 'prerequisite', source: 'b', target: 'a' }];
  const manifest = review();
  manifest.prerequisiteEdges = ['ab', 'ba'];
  assert.throws(() => convertRoadmap(cyclic, manifest, 'hash'), /cycle/);
});

test('approved category or title nodes are still excluded as job skills', () => {
  const manifest = review();
  manifest.approvedNodes.title = { name: 'Getting started', skillId: 'intro', type: 'SKILL', domain: 'TEST' };
  manifest.approvedNodes.a.type = 'CATEGORY';
  assert.deepEqual(convertRoadmap(raw(), manifest, 'hash').catalog.skills.map(skill => skill.id), ['beta']);
});

test('supports the documented topic-slug@node-id.md layout using only authorized local files', () => {
  const prefix = path.resolve('target/catalog-import-tests');
  fs.mkdirSync(prefix, { recursive: true });
  const directory = fs.mkdtempSync(path.join(prefix, 'fixture-'));
  try {
    fs.writeFileSync(path.join(directory, 'alpha@a.md'), '# Alpha\n\nOriginal synthetic body, not copied to output.');
    const source = readLocalSource(directory);
    assert.deepEqual(source.raw.nodes, [{ id: 'a', label: 'Alpha', type: 'topic' }]);
    assert.equal(source.sourceHash.length, 64);
    assert.ok(!JSON.stringify(source.raw).includes('synthetic body'));
  } finally {
    const resolved = path.resolve(directory);
    assert.ok(resolved.startsWith(prefix + path.sep));
    fs.rmSync(resolved, { recursive: true });
  }
});
