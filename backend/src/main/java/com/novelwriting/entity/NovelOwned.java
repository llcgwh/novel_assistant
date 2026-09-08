package com.novelwriting.entity;

/** A record whose IDs and associations must stay within one novel. */
public interface NovelOwned {
    Long getId();
    Long getNovelId();
}
