package com.novelwriting.service;

import com.novelwriting.entity.Character;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.CharacterRepository;
import com.novelwriting.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CharacterServiceTest {
    @Mock CharacterRepository characters;
    @Mock TagRepository tags;
    @InjectMocks CharacterService service;
    Character character;

    @BeforeEach void setUp() {
        character = new Character();
        character.setId(10L);
        character.setNovelId(1L);
        character.setName("Original");
    }

    @Test void foreignNovelCannotReadOrMutateCharacter() {
        when(characters.findById(10L)).thenReturn(Optional.of(character));
        assertTrue(service.getCharacterById(2L, 10L).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.updateCharacter(2L, 10L, new Character()));
        assertThrows(ResponseStatusException.class, () -> service.deleteCharacter(2L, 10L));
        assertThrows(ResponseStatusException.class, () -> service.addTagToCharacter(2L, 10L, 20L));
        assertThrows(ResponseStatusException.class, () -> service.removeTagFromCharacter(2L, 10L, 20L));
        assertThrows(ResponseStatusException.class, () -> service.setCharacterTags(2L, 10L, Set.of(20L)));
        assertEquals("Original", character.getName());
        verify(characters, never()).save(any());
        verify(characters, never()).delete(any(Character.class));
        verifyNoInteractions(tags);
    }

    @Test void foreignTagsCannotBeAttached() {
        Tag foreign = new Tag();
        foreign.setId(20L);
        foreign.setNovelId(2L);
        when(characters.findById(10L)).thenReturn(Optional.of(character));
        when(tags.findById(20L)).thenReturn(Optional.of(foreign));
        assertThrows(ResponseStatusException.class, () -> service.addTagToCharacter(1L, 10L, 20L));
        assertThrows(ResponseStatusException.class, () -> service.setCharacterTags(1L, 10L, Set.of(20L)));
        assertTrue(character.getTags().isEmpty());
        verify(characters, never()).save(any());
    }

    @Test void owningNovelCanUpdateAndDelete() {
        when(characters.findById(10L)).thenReturn(Optional.of(character));
        when(characters.save(character)).thenReturn(character);
        Character update = new Character();
        update.setName("Updated");
        assertEquals("Updated", service.updateCharacter(1L, 10L, update).getName());
        service.deleteCharacter(1L, 10L);
        verify(characters).delete(character);
    }

    @Test void creationCannotOverwriteAnExistingId() {
        when(characters.save(character)).thenReturn(character);
        service.createCharacter(character);
        assertNull(character.getId());
        verify(characters).save(character);
    }

    @Test void creationRejectsForeignTags() {
        Tag foreign = new Tag();
        foreign.setId(20L);
        foreign.setNovelId(2L);
        character.setTags(Set.of(foreign));
        when(tags.findById(20L)).thenReturn(Optional.of(foreign));
        assertThrows(ResponseStatusException.class, () -> service.createCharacter(character));
        verify(characters, never()).save(any());
    }
}
