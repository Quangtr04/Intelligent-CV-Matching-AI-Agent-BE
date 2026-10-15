-- Preserve recommendations as they were presented when a roadmap was created.
ALTER TABLE roadmaps
    ADD COLUMN learning_recommendations_snapshot jsonb,
    ADD CONSTRAINT ck_roadmaps_learning_recommendations_snapshot
        CHECK (learning_recommendations_snapshot IS NULL
            OR jsonb_typeof(learning_recommendations_snapshot) = 'object');

ALTER TABLE roadmap_node_resources
    ADD COLUMN resource_snapshot jsonb,
    ADD CONSTRAINT ck_roadmap_node_resources_resource_snapshot
        CHECK (resource_snapshot IS NULL OR jsonb_typeof(resource_snapshot) = 'object');

-- Existing associations retain the catalog data available at migration time,
-- including resources that have since been deactivated.
UPDATE roadmap_node_resources nr
SET resource_snapshot = to_jsonb(r)
FROM learning_resources r
WHERE r.id = nr.resource_id AND nr.resource_snapshot IS NULL;
