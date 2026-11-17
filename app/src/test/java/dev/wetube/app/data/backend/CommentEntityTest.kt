package dev.wetube.app.data.backend

import dev.wetube.app.data.model.Comment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the reading of the split comment shape.
 *
 * The names and the numbers here are copied from real WEB answers. A thread used
 * to arrive whole, as one `commentRenderer`; now it is three tables — a view
 * model naming a `commentKey`, a `commentEntityPayload` under that key with the
 * comment, and an `engagementToolbarStateEntityPayload` under a separate
 * `toolbarStateKey` with whether this account liked it. The buttons' commands
 * are not on the comment at all: they are on an
 * `engagementToolbarSurfaceEntityPayload` under the thread's `toolbarSurfaceKey`.
 * A signed-out answer still carries that surface entity, but its commands are
 * stubs with only a tracking parameter and no action, which is why a signed-out
 * comment offers no buttons.
 */
class CommentEntityTest {

    @Test
    fun `the split shape is joined by the keys its two tables share`() {
        val page = page().commentPageFrom { null }

        assertEquals(1, page.comments.size)
        val comment = page.comments.single()
        assertEquals("@DrJonathanTam", comment.author)
        assertEquals("A pinned word.", comment.text)
        assertEquals(83L, comment.likes)
        assertEquals(21, comment.replyCount)
        assertEquals("11 days ago", comment.publishedText)
        assertEquals("UgxXplBd_srVeQWcO354AaABAg", comment.id)
        assertEquals("https://yt3.ggpht.com/AAAA=s88", comment.avatar)
        assertFalse(comment.liked)
        assertFalse(comment.own)
    }

    @Test
    fun `a liked comment reports the count its like is in`() {
        val comment = page("TOOLBAR_LIKE_STATE_LIKED").commentPageFrom { null }.comments.single()

        assertTrue(comment.liked)
        assertEquals(84L, comment.likes)
    }

    @Test
    fun `the next page is the last item of the thread list, not a thread's replies`() {
        assertEquals("NEXT-PAGE", page().commentPageFrom { null }.next)
    }

    @Test
    fun `a thread points at its replies by a token and the server's own count`() {
        val replies = page().commentPageFrom { null }.comments.single().replies

        assertEquals("REPLY-PAGE", replies?.token)
        assertEquals("21 replies", replies?.label)
    }

    @Test
    fun `a thread without replies carries no pointer`() {
        assertNull(signedInPage().commentPageFrom { null }.comments.single().replies)
    }

    @Test
    fun `a replies page is read the same way as a first page`() {
        val page = repliesPage().commentPageFrom { null }

        assertEquals(
            listOf(
                "UgxXplBd_srVeQWcO354AaABAg.AcqQkD1gCGnAd8qtcRYJJ9",
                "UgxXplBd_srVeQWcO354AaABAg.AhD8qkD1gCGn",
            ),
            page.comments.map { it.id },
        )
        assertEquals(listOf("@LunaFreya-q6c", "@ST-uf5qp"), page.comments.map { it.author })
        assertEquals(
            listOf("Yes do a video on Japan and Brazil if you can.", "Same here."),
            page.comments.map { it.text },
        )
        assertTrue(page.comments.first().liked)
        assertEquals(2L, page.comments.first().likes)
        assertFalse(page.comments.last().liked)
        assertEquals("MORE-REPLIES", page.next)
    }

    @Test
    fun `a reply carries no thread of its own`() {
        // A reply is listed as a bare view model, so it holds no
        // `commentRepliesRenderer` — which is what keeps threading one level
        // deep no matter what the row's reply count says.
        val page = repliesPage().commentPageFrom { null }

        assertTrue(page.comments.all { it.replies == null })
    }

    @Test
    fun `each reply keeps the commands its own surface carries`() {
        val page = repliesPage().commentPageFrom { null }

        assertEquals("REPLY-LIKE-1", page.comments[0].actions.like)
        assertEquals("REPLY-REPLY-2", page.comments[1].actions.reply)
    }

    @Test
    fun `a signed out page offers no commands`() {
        val actions = page().commentPageFrom { null }.comments.single().actions

        assertNull(actions.like)
        assertNull(actions.unlike)
        assertNull(actions.reply)
        assertFalse(actions.canLike)
        assertFalse(actions.canReply)
    }

    @Test
    fun `the commands a signed in page carries are read off its toolbar surface`() {
        val actions = signedInPage().commentPageFrom { null }.comments.single().actions

        assertEquals("LIKE-PARAMS", actions.like)
        assertEquals("UNLIKE-PARAMS", actions.unlike)
        assertEquals("REPLY-PARAMS", actions.reply)
        assertTrue(actions.canLike)
        assertTrue(actions.canReply)
    }

    @Test
    fun `a surface entity without actions offers no commands`() {
        val actions = page().commentPageFrom { null }.comments.single().actions

        // The entity is there, named by the thread, but its commands are the
        // stubs a signed-out answer sends — no `action` to hand back.
        assertFalse(actions.canLike)
        assertFalse(actions.canReply)
    }

    @Test
    fun `an entity of this account is marked as its own`() {
        val comment = signedInPage().commentPageFrom { null }.comments.single()

        assertTrue(comment.own)
        assertFalse(comment.liked)
    }

    @Test
    fun `a thread whose entity is missing is left out rather than half read`() {
        val body = parse(
            """
            {
              "commentThreadRenderer": {
                "commentViewModel": { "commentViewModel": { "commentKey": "GONE" } }
              },
              "commentEntityPayload": {
                "key": "ANOTHER",
                "properties": {
                  "commentId": "UgxOther",
                  "content": { "content": "A comment nobody points at." }
                }
              }
            }
            """.trimIndent(),
        )

        assertTrue(body.commentPageFrom { null }.comments.isEmpty())
    }

    @Test
    fun `a page without entities is handed to the legacy reader`() {
        val body = parse("""{"commentThreadRenderer": {"comment": {"commentRenderer": {}}}}""")
        val seen = mutableListOf<JsonObject>()

        val page = body.commentPageFrom { thread ->
            seen += thread
            Comment(author = "Legacy", text = "Whole renderer")
        }

        assertEquals(1, seen.size)
        assertEquals(listOf("Legacy"), page.comments.map { it.author })
    }

    /**
     * A page as a signed-out WEB answer states it: the thread names its entity
     * and its toolbar surface, the entity carries the counts and the state key,
     * and the surface carries the stub commands that do nothing.
     */
    private fun page(liked: String = "TOOLBAR_LIKE_STATE_INDIFFERENT"): JsonObject = parse(
        """
        {
          "onResponseReceivedEndpoints": [
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  {
                    "commentThreadRenderer": {
                      "commentViewModel": {
                        "commentViewModel": {
                          "commentKey": "ENTITY-KEY",
                          "toolbarStateKey": "TOOLBAR-KEY",
                          "toolbarSurfaceKey": "SURFACE-KEY"
                        }
                      },
                      "replies": {
                        "commentRepliesRenderer": {
                          "contents": [
                            {
                              "continuationItemRenderer": {
                                "continuationEndpoint": {
                                  "continuationCommand": { "token": "REPLY-PAGE" }
                                }
                              }
                            }
                          ],
                          "viewReplies": {
                            "buttonRenderer": {
                              "text": { "runs": [ { "text": "21 replies" } ] },
                              "icon": { "iconType": "EXPAND" }
                            }
                          },
                          "hideReplies": {
                            "buttonRenderer": {
                              "text": { "runs": [ { "text": "21 replies" } ] },
                              "icon": { "iconType": "COLLAPSE" }
                            }
                          },
                          "targetId": "comment-replies-item-UgxXplBd_srVeQWcO354AaABAg"
                        }
                      }
                    }
                  },
                  {
                    "continuationItemRenderer": {
                      "continuationEndpoint": {
                        "continuationCommand": { "token": "NEXT-PAGE" }
                      }
                    }
                  }
                ]
              }
            },
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  {
                    "commentEntityPayload": {
                      "key": "ENTITY-KEY",
                      "properties": {
                        "commentId": "UgxXplBd_srVeQWcO354AaABAg",
                        "content": { "content": "A pinned word." },
                        "publishedTime": "11 days ago",
                        "replyLevel": 0,
                        "toolbarStateKey": "TOOLBAR-KEY"
                      },
                      "author": {
                        "displayName": "@DrJonathanTam",
                        "avatarThumbnailUrl": "https://yt3.ggpht.com/AAAA=s88",
                        "isCurrentUser": false
                      },
                      "toolbar": {
                        "likeCountNotliked": "83",
                        "likeCountLiked": "84",
                        "replyCount": "21"
                      }
                    }
                  }
                ]
              }
            },
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  {
                    "engagementToolbarStateEntityPayload": {
                      "key": "TOOLBAR-KEY",
                      "likeState": "$liked"
                    }
                  },
                  {
                    "engagementToolbarSurfaceEntityPayload": {
                      "key": "SURFACE-KEY",
                      "isEngagementToolbar": true,
                      "likeCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9"
                        }
                      },
                      "unlikeCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9"
                        }
                      },
                      "replyCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9"
                        }
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent(),
    )

    /**
     * The same page as a signed-in answer states it: the same three tables, but
     * now the surface entity's commands each carry the parameter the write
     * takes — the `action` of a `perform_comment_action` for like and unlike,
     * and the `createReplyParams` of the reply dialog's submit button.
     *
     * The reply dialog also carries a `createCommentParams` under a nested
     * `createCommentEndpoint`, which is the *composer's* parameter for a new
     * top-level comment. It is here on purpose: taking it for the reply's would
     * post the reply to the video instead of to the comment, and only a fixture
     * holding both can pin which one is read.
     *
     * The parameters are placeholders; their position under each command is what
     * is pinned, and that nesting is the one a device log confirmed.
     */
    private fun signedInPage(): JsonObject = parse(
        """
        {
          "onResponseReceivedEndpoints": [
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  {
                    "commentThreadRenderer": {
                      "commentViewModel": {
                        "commentViewModel": {
                          "commentKey": "ENTITY-KEY",
                          "toolbarStateKey": "TOOLBAR-KEY",
                          "toolbarSurfaceKey": "SURFACE-KEY"
                        }
                      }
                    }
                  }
                ]
              }
            },
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  {
                    "commentEntityPayload": {
                      "key": "ENTITY-KEY",
                      "properties": {
                        "commentId": "UgxMine",
                        "content": { "content": "Mine" },
                        "toolbarStateKey": "TOOLBAR-KEY"
                      },
                      "author": { "displayName": "@me", "isCurrentUser": true },
                      "toolbar": {
                        "likeCountNotliked": "0",
                        "likeCountLiked": "1",
                        "replyCount": "0"
                      }
                    }
                  }
                ]
              }
            },
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  {
                    "engagementToolbarStateEntityPayload": {
                      "key": "TOOLBAR-KEY",
                      "likeState": "TOOLBAR_LIKE_STATE_INDIFFERENT"
                    }
                  },
                  {
                    "engagementToolbarSurfaceEntityPayload": {
                      "key": "SURFACE-KEY",
                      "isEngagementToolbar": true,
                      "likeCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9",
                          "commandMetadata": {
                            "webCommandMetadata": {
                              "sendPost": true,
                              "apiUrl": "/youtubei/v1/comment/perform_comment_action"
                            }
                          },
                          "performCommentActionEndpoint": { "action": "LIKE-PARAMS" }
                        }
                      },
                      "unlikeCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9",
                          "performCommentActionEndpoint": { "action": "UNLIKE-PARAMS" }
                        }
                      },
                      "replyCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwjqjva9",
                          "commandMetadata": {
                            "webCommandMetadata": { "ignoreNavigation": true }
                          },
                          "createCommentReplyDialogEndpoint": {
                            "dialog": {
                              "commentReplyDialogRenderer": {
                                "replyButton": {
                                  "buttonRenderer": {
                                    "serviceEndpoint": {
                                      "commandMetadata": {
                                        "webCommandMetadata": {
                                          "sendPost": true,
                                          "apiUrl": "/youtubei/v1/comment/create_comment_reply"
                                        }
                                      },
                                      "createCommentReplyEndpoint": {
                                        "createReplyParams": "REPLY-PARAMS"
                                      }
                                    }
                                  }
                                },
                                "placeholderText": {
                                  "runs": [ { "text": "Add a reply..." } ]
                                },
                                "emojiButton": {
                                  "buttonRenderer": {
                                    "command": {
                                      "createCommentEndpoint": {
                                        "createCommentParams": "COMPOSER-PARAMS"
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent(),
    )

    /**
     * A replies page as a real WEB answer states it.
     *
     * It differs from a first page in exactly two ways, both copied from the
     * capture: the items are bare `commentViewModel`s carrying their keys
     * directly — there is no `commentThreadRenderer` around them, because a
     * thread holds replies rather than threads — and the page's own continuation
     * is the trailing item's button rather than a bare button command.
     */
    private fun repliesPage(): JsonObject = parse(
        """
        {
          "onResponseReceivedEndpoints": [
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  {
                    "commentViewModel": {
                      "commentKey": "REPLY-ENTITY-1",
                      "sharedKey": "EgAgKygB",
                      "toolbarStateKey": "REPLY-TOOLBAR-1",
                      "toolbarSurfaceKey": "REPLY-SURFACE-1",
                      "commentId": "UgxXplBd_srVeQWcO354AaABAg.AcqQkD1gCGnAd8qtcRYJJ9",
                      "commentSurfaceKey": "REPLY-SURFACE-KEY-1",
                      "sharedSurfaceKey": "EhVWSUVXSU5HX0NPTlRFWFRfV0FUQ0gg9AIoAQ%3D%3D",
                      "inlineRepliesKey": "empty_inline_replies",
                      "rendererContext": {
                        "loggingContext": {
                          "loggingDirectives": { "trackingParams": "CEAQtnUYZSIT" }
                        }
                      }
                    }
                  },
                  {
                    "commentViewModel": {
                      "commentKey": "REPLY-ENTITY-2",
                      "sharedKey": "EgAgKygB",
                      "toolbarStateKey": "REPLY-TOOLBAR-2",
                      "toolbarSurfaceKey": "REPLY-SURFACE-2",
                      "commentId": "UgxXplBd_srVeQWcO354AaABAg.AhD8qkD1gCGn",
                      "commentSurfaceKey": "REPLY-SURFACE-KEY-2",
                      "sharedSurfaceKey": "EhVWSUVXSU5HX0NPTlRFWFRfV0FUQ0gg9AIoAQ%3D%3D",
                      "inlineRepliesKey": "empty_inline_replies",
                      "rendererContext": {
                        "loggingContext": {
                          "loggingDirectives": { "trackingParams": "CEAQtnUYZSIT" }
                        }
                      }
                    }
                  },
                  {
                    "continuationItemRenderer": {
                      "trigger": "CONTINUATION_TRIGGER_ON_ITEM_SHOWN",
                      "button": {
                        "buttonRenderer": {
                          "style": "STYLE_TEXT",
                          "size": "SIZE_DEFAULT",
                          "text": { "runs": [ { "text": "Show more replies" } ] },
                          "trackingParams": "CGQQtnUYZSIT",
                          "command": {
                            "clickTrackingParams": "CGQQtnUYZSIT",
                            "continuationCommand": {
                              "token": "MORE-REPLIES",
                              "request": "CONTINUATION_REQUEST_TYPE_WATCH_NEXT"
                            }
                          }
                        }
                      }
                    }
                  }
                ]
              }
            }
          ],
          "frameworkUpdates": {
            "entityBatchUpdate": {
              "mutations": [
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "REPLY-ENTITY-1",
                      "properties": {
                        "commentId": "UgxXplBd_srVeQWcO354AaABAg.AcqQkD1gCGnAd8qtcRYJJ9",
                        "content": {
                          "content": "Yes do a video on Japan and Brazil if you can."
                        },
                        "publishedTime": "4 days ago",
                        "replyLevel": 1,
                        "authorButtonA11y": "@LunaFreya-q6c",
                        "toolbarStateKey": "REPLY-TOOLBAR-1"
                      },
                      "author": {
                        "channelId": "UCzq_HlttvCIhB0eELOTOOJg",
                        "displayName": "@LunaFreya-q6c",
                        "avatarThumbnailUrl": "https://yt3.ggpht.com/BBBB=s88",
                        "isCurrentUser": false
                      },
                      "toolbar": {
                        "likeCountNotliked": "1",
                        "likeCountLiked": "2",
                        "replyCount": ""
                      }
                    }
                  }
                },
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "REPLY-ENTITY-2",
                      "properties": {
                        "commentId": "UgxXplBd_srVeQWcO354AaABAg.AhD8qkD1gCGn",
                        "content": { "content": "Same here." },
                        "publishedTime": "3 days ago",
                        "replyLevel": 1,
                        "authorButtonA11y": "@ST-uf5qp",
                        "toolbarStateKey": "REPLY-TOOLBAR-2"
                      },
                      "author": {
                        "channelId": "UCabc",
                        "displayName": "@ST-uf5qp",
                        "isCurrentUser": false
                      },
                      "toolbar": {
                        "likeCountNotliked": "0",
                        "likeCountLiked": "1",
                        "replyCount": ""
                      }
                    }
                  }
                },
                {
                  "payload": {
                    "engagementToolbarStateEntityPayload": {
                      "key": "REPLY-TOOLBAR-1",
                      "likeState": "TOOLBAR_LIKE_STATE_LIKED"
                    }
                  }
                },
                {
                  "payload": {
                    "engagementToolbarStateEntityPayload": {
                      "key": "REPLY-TOOLBAR-2",
                      "likeState": "TOOLBAR_LIKE_STATE_INDIFFERENT"
                    }
                  }
                },
                {
                  "payload": {
                    "engagementToolbarSurfaceEntityPayload": {
                      "key": "REPLY-SURFACE-1",
                      "isEngagementToolbar": true,
                      "likeCommand": {
                        "innertubeCommand": {
                          "clickTrackingParams": "CAAQg2ciEwinv_WngI-YAxXDpawCHWskGt8",
                          "performCommentActionEndpoint": { "action": "REPLY-LIKE-1" }
                        }
                      },
                      "unlikeCommand": {
                        "innertubeCommand": {
                          "performCommentActionEndpoint": { "action": "REPLY-UNLIKE-1" }
                        }
                      },
                      "replyCommand": {
                        "innertubeCommand": {
                          "createCommentReplyDialogEndpoint": {
                            "dialog": {
                              "commentReplyDialogRenderer": {
                                "replyButton": {
                                  "buttonRenderer": {
                                    "serviceEndpoint": {
                                      "createCommentReplyEndpoint": {
                                        "createReplyParams": "REPLY-REPLY-1"
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                {
                  "payload": {
                    "engagementToolbarSurfaceEntityPayload": {
                      "key": "REPLY-SURFACE-2",
                      "isEngagementToolbar": true,
                      "likeCommand": {
                        "innertubeCommand": {
                          "performCommentActionEndpoint": { "action": "REPLY-LIKE-2" }
                        }
                      },
                      "replyCommand": {
                        "innertubeCommand": {
                          "createCommentReplyDialogEndpoint": {
                            "dialog": {
                              "commentReplyDialogRenderer": {
                                "replyButton": {
                                  "buttonRenderer": {
                                    "serviceEndpoint": {
                                      "createCommentReplyEndpoint": {
                                        "createReplyParams": "REPLY-REPLY-2"
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent(),
    )

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).obj()!!
}
