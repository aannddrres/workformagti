package ge.magti.portal.video;

import ge.magti.portal.domain.Tag;
import ge.magti.portal.domain.TagMapping;
import ge.magti.portal.repository.TagMappingRepository;
import ge.magti.portal.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the
 * normalized tags/tags_mapping tables in sync with an item's flat
 * comma-separated tags string, creating new Tag rows on the fly. Shared
 * by both articles and videos, so it is its own class.
 */
@Service
public class TagSyncService {

    private final TagRepository tagRepository;
    private final TagMappingRepository tagMappingRepository;

    public TagSyncService(TagRepository tagRepository, TagMappingRepository tagMappingRepository) {
        this.tagRepository = tagRepository;
        this.tagMappingRepository = tagMappingRepository;
    }

    @Transactional
    public void sync(String itemType, Long itemId, String tagsCsv) {
        tagMappingRepository.deleteByItemTypeAndItemId(itemType, itemId);
        // Hibernate's default flush ordering runs INSERTs before DELETEs
        // regardless of call order, so without this flush, re-adding a tag
        // this item already had violates uq_tag_mapping_item -- the old row
        // hasn't hit the DB yet when the new one's INSERT runs.
        tagMappingRepository.flush();

        if (tagsCsv == null || tagsCsv.isBlank()) {
            return;
        }

        Set<String> names = Arrays.stream(tagsCsv.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String name : names) {
            Tag tag = tagRepository.findByName(name).orElseGet(() -> {
                Tag created = new Tag();
                created.setName(name);
                return tagRepository.saveAndFlush(created);
            });
            TagMapping mapping = new TagMapping();
            mapping.setTagId(tag.getId());
            mapping.setItemType(itemType);
            mapping.setItemId(itemId);
            tagMappingRepository.save(mapping);
        }
    }
}
