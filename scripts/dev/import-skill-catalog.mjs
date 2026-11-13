import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const fold = (value) => value.normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').toLowerCase().trim();
const skillTypes = new Set(['SKILL', 'TECHNOLOGY', 'TOOL', 'COMPETENCY', 'KNOWLEDGE', 'SOFT_SKILL', 'LANGUAGE']);
const decorative = new Set(['title', 'group', 'label', 'button', 'link', 'section', 'legend']);
const permissive = new Set(['MIT', 'Apache-2.0', 'BSD-3-Clause', 'CC0-1.0', 'CC-BY-4.0']);
const unique = (values) => [...new Set(values)];
const stableId = (value) => {
  if (!/^[a-z0-9][a-z0-9-]*$/.test(value ?? '')) throw Error('A reviewed stable canonical ID is required');
  return value;
};

export function validatePermission(review) {
  const source = review?.source;
  if (!review.catalogVersion || !source?.id || !source.url || !source.revision || !source.license
      || !source.permissionEvidence || !source.permissionKind) throw Error('Missing provenance or permission evidence');
  if (/roadmap\.sh|developer-roadmap/i.test(source.url) && source.permissionKind !== 'WRITTEN_PERMISSION') {
    throw Error('roadmap.sh content requires documented written permission for this import path');
  }
  if (!['USER_OWNED', 'WRITTEN_PERMISSION', 'PERMISSIVE_LICENSE'].includes(source.permissionKind)) {
    throw Error('Unsupported reuse permission');
  }
  if (source.permissionKind === 'PERMISSIVE_LICENSE' && !permissive.has(source.license)) {
    throw Error('License is not in the importer allowlist; use documented written permission');
  }
}

/** Converts only explicitly reviewed nodes from a local, authorized export. Never fetches a website. */
export function convertRoadmap(raw, review, sourceHash, base = { skills: [], topics: [] }) {
  validatePermission(review);
  if (!Array.isArray(raw.nodes) || raw.nodes.length > 10000) throw Error('Expected at most 10000 source nodes');
  const nodes = new Map();
  for (const node of raw.nodes) {
    if (!node.id || nodes.has(node.id)) throw Error('Missing or duplicate source node ID');
    nodes.set(node.id, node);
  }
  const skills = new Map();
  const topics = new Map();
  const sources = [];
  const sourceTopics = new Map();
  const skipped = [];
  const knownSkills = new Map(base.skills.map(skill => [skill.id, skill]));
  for (const [nodeId, decision] of Object.entries(review.approvedNodes ?? {})) {
    const node = nodes.get(nodeId);
    if (!node) throw Error(`Reviewed node does not exist: ${nodeId}`);
    if (decorative.has(node.type) || decision.type === 'CATEGORY') {
      skipped.push({ nodeId, reason: 'DECORATIVE_OR_CATEGORY' });
      continue;
    }
    const label = String(node.label ?? node.data?.label ?? '').replace(/<[^>]*>/g, '').trim();
    if (!label || !decision.name || label.length > 100) throw Error('Invalid source topic label');
    if (![decision.name, ...(decision.aliases ?? [])].some(name => fold(name) === fold(label))) {
      throw Error(`Source label must be explicitly reviewed as canonical name or alias: ${nodeId}`);
    }
    const topicId = stableId(decision.topicId ?? (decision.skillId ? `${decision.skillId}-learning` : null));
    const sourceId = `${review.source.id}:${nodeId}`;
    sources.push({ id: sourceId, url: review.source.url,
      revision: `${review.source.revision}/node:${nodeId}/sha256:${sourceHash}`, license: review.source.license,
      permissionEvidence: review.source.permissionEvidence });
    if (topics.has(topicId)) {
      const previous = topics.get(topicId);
      if (fold(previous.title) !== fold(label)) throw Error('Conflicting learning topic identity');
      previous.sourceReferences.push(sourceId);
    } else topics.set(topicId, { id: topicId, title: label, type: 'LEARNING_TOPIC', prerequisites: [],
      sourceReferences: [sourceId] });
    sourceTopics.set(nodeId, topicId);
    if (decision.type === 'LEARNING_TOPIC') continue;
    if (!skillTypes.has(decision.type) || !decision.domain) throw Error('Reviewed skill type and domain are required');
    const id = stableId(decision.skillId);
    const known = knownSkills.get(id);
    if (known && (fold(known.name) !== fold(decision.name) || known.type !== decision.type)) {
      throw Error('Import would change an existing canonical identity');
    }
    const skill = { id, name: known?.name ?? decision.name, domain: known?.domain ?? decision.domain,
      type: decision.type, aliases: unique(decision.aliases ?? []), ambiguousAliases: decision.ambiguousAliases ?? [],
      contextPattern: known?.contextPattern ?? decision.contextPattern ?? '', recognitionPatterns: [],
      relatedSkills: decision.relatedSkills ?? [], learningTopics: [topicId], sourceReferences: [sourceId] };
    if (skills.has(id)) {
      const previous = skills.get(id);
      if (fold(previous.name) !== fold(skill.name) || previous.type !== skill.type) throw Error('Conflicting skill ID');
      previous.aliases = unique([...previous.aliases, ...skill.aliases]);
      previous.learningTopics = unique([...previous.learningTopics, topicId]);
      previous.sourceReferences.push(sourceId);
    } else skills.set(id, skill);
  }
  const edges = new Map((raw.edges ?? []).map(edge => [edge.id, edge]));
  for (const id of review.prerequisiteEdges ?? []) {
    const edge = edges.get(id);
    if (!edge || (edge.type !== 'prerequisite' && edge.data?.relationship !== 'prerequisite')) {
      throw Error('Drawing order or an untyped connector cannot establish a prerequisite');
    }
    const prerequisite = sourceTopics.get(edge.source);
    const dependent = sourceTopics.get(edge.target);
    if (!prerequisite || !dependent) throw Error('Prerequisites may reference only reviewed selected topics');
    topics.get(dependent).prerequisites = unique([...topics.get(dependent).prerequisites, prerequisite]);
  }
  const knownIds = new Set([...knownSkills.keys(), ...skills.keys()]);
  for (const skill of skills.values()) {
    if (skill.relatedSkills.some(id => !knownIds.has(id))) throw Error('Unknown related skill');
  }
  const aliasOwners = new Map();
  for (const skill of [...base.skills, ...skills.values()]) {
    for (const alias of unique([skill.name, ...skill.aliases].map(fold))) {
      const previous = aliasOwners.get(alias);
      if (previous && previous.id !== skill.id
          && (!previous.ambiguousAliases?.map(fold).includes(alias) || !skill.ambiguousAliases.map(fold).includes(alias))) {
        throw Error(`Conflicting alias: ${alias}`);
      }
      aliasOwners.set(alias, skill);
    }
  }
  const visit = (id, chain = new Set()) => {
    if (chain.has(id)) throw Error('Prerequisite cycle');
    const next = new Set([...chain, id]);
    for (const parent of topics.get(id).prerequisites) visit(parent, next);
  };
  for (const id of topics.keys()) visit(id);
  return { catalog: { catalogVersion: review.catalogVersion, sources,
    skills: [...skills.values()].sort((a, b) => a.id.localeCompare(b.id)),
    topics: [...topics.values()].sort((a, b) => a.id.localeCompare(b.id)) },
    report: { sourceHash, selectedSkills: skills.size, selectedTopics: topics.size,
      skippedNodes: nodes.size - sourceTopics.size, ignoredEdges: edges.size - (review.prerequisiteEdges?.length ?? 0), skipped } };
}

export function readLocalSource(input) {
  if (!fs.statSync(input).isDirectory()) {
    const bytes = fs.readFileSync(input);
    return { raw: JSON.parse(bytes), sourceHash: crypto.createHash('sha256').update(bytes).digest('hex') };
  }
  const files = fs.readdirSync(input).filter(name => /^[^/]+@[^/]+\.md$/.test(name)).sort();
  const digest = crypto.createHash('sha256');
  const nodes = [];
  for (const name of files) {
    const file = path.join(input, name);
    if (fs.lstatSync(file).isSymbolicLink()) throw Error('Source symlinks are not supported');
    const bytes = fs.readFileSync(file);
    const label = bytes.toString('utf8').match(/^#\s+(.+)$/m)?.[1];
    if (!label) throw Error(`Missing topic title: ${name}`);
    digest.update(name).update('\0').update(bytes);
    nodes.push({ id: name.match(/@([^@]+)\.md$/)[1], label, type: 'topic' });
  }
  return { raw: { nodes, edges: [] }, sourceHash: digest.digest('hex') };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [input, reviewFile, output] = process.argv.slice(2);
  if (!input || !reviewFile || !output) throw Error('Usage: node import-skill-catalog.mjs SOURCE REVIEW.json OUTPUT.json');
  const review = JSON.parse(fs.readFileSync(reviewFile, 'utf8'));
  validatePermission(review);
  const { raw, sourceHash } = readLocalSource(input);
  const base = JSON.parse(fs.readFileSync('src/main/resources/skills/catalog.json', 'utf8'));
  const result = convertRoadmap(raw, review, sourceHash, base);
  fs.writeFileSync(output, JSON.stringify(result.catalog, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify(result.report, null, 2));
}
