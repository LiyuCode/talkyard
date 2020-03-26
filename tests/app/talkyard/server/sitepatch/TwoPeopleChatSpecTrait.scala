/**
 * Copyright (c) 2020 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
//NEXT
package talkyard.server.sitepatch

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.EdHttp.ResultException
import debiki.TextAndHtmlMaker
import debiki.dao._
import org.scalatest._
import scala.collection.immutable


trait TwoPeopleChatSpecTrait {
  self: SitePatcherAppSpec =>


  def makeTwoPeopleChatTests()  {
    lazy val siteDao = globals.siteDao(site.id)

    lazy val (site, forum, oldPageId, oldPagePosts, owen, merrylMember, dao) =
      createSiteWithOneCatPageMember("2-ppl-chat", pageExtId = None, ownerUsername = "owen")

    var prevSiteDump: SitePatch = null

    lazy val alice = createPasswordUserGetDetails("alice_un", dao, extId = Some("Alice-ExtId"))
    lazy val bob = createPasswordUserGetDetails("bob_un", dao, extId = Some("Bob-ExtId"))
    lazy val sarah = createPasswordUserGetDetails("sarah_un", dao, extId = Some("Sarah-ExtId"))
    lazy val sanjo = createPasswordUserGetDetails("sanjo_un", dao, extId = Some("Sanjo-ExtId"))

    lazy val chatPagePatch = SimplePagePatch(
      extId = "chatPageExtId",
      pageType = Some(PageType.PrivateChat),
      categoryRef = Some(s"tyid:${forum.defaultCategoryId}"),
      authorRef = alice.extId.map("extid:" + _),
      pageMemberRefs = Vector(alice.extIdAsRef.get, bob.extIdAsRef.get),
      title = "Chat Page Title",
      body = "Chat between Alice and Bob")

    lazy val chatPagePatch2 = chatPagePatch.copy(
      extId = "chatPage2ExtId",
      authorRef = sarah.extId.map("extid:" + _),
      pageMemberRefs = Vector(sarah.extIdAsRef.get, sanjo.extIdAsRef.get),
      title = "Chat Page Title 2",
      body = "Chat between Sarah and Sanjo")


    def makeChatMessage(extId: ExtId, pagePatch: SimplePagePatch,
        author: UserInclDetails, text: String) =
      SimplePostPatch(
        extId = extId,
        postType = PostType.ChatMessage,
        pageRef = ParsedRef.ExternalId(pagePatch.extId),
        parentNr = None,
        authorRef = author.extIdAsRef.get,
        body = text)


    "Create anew site with a chat topic" in {
      site
      prevSiteDump = SitePatchMaker(context).loadSiteDump(site.id)
    }


    lazy val aliceSaysHiBobMessage = makeChatMessage(
      extId = "aliceSaysHiBobMessage-ext-id",
      chatPagePatch,
      alice,
      "Hi Bob, Alice here")

    "Upsert a chat topic with a single message from Alice to Bob" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        pagePatches = Vector(chatPagePatch),
        postPatches = Vector(aliceSaysHiBobMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 05697826" in {
      var alicesPost: Post = null
      checkChanges(
        numNewPages = 1,         // Alice's message to Bob
        numNewPosts =  3,        // title, body, message
        numNewPostsByAlice = 3,  //  – "" —
        // Alice's message is the last post:
        lastPostBy = alice.id,
        lastPostCheckFn = (lastPost: Post) => {
          alicesPost = lastPost
          //alicesPost.pageId mustBe ???
          alicesPost.nr mustBe FirstReplyNr
          alicesPost.createdById mustBe alice.id
          alicesPost.currentSource mustBe aliceSaysHiBobMessage.body
          alicesPost.currentRevisionById mustBe alice.id
          alicesPost.approvedHtmlSanitized.get must include(aliceSaysHiBobMessage.body)
        },
        // One notf to Bob about Alice's message:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe alice.id
          notf.toUserId mustBe bob.id
          // Hmm this'll currently be a NotificationType.NewPost, because
          // new posts considered before new messages [PATCHNOTF].
          //notf.tyype mustBe NotificationType.Message
          // For now:  (this should be the page body, created just before.
          // But would want this to be the chat message = alicesPost.id - 0, instead)
          notf.uniquePostId mustBe (alicesPost.id - 1)
        })
    }

    def checkChanges(numNewPages: Int = 0, numNewPosts: Int,
          numNewPostsByAlice: Int = 0, numNewPostsByBob: Int = 0,
          numNewPostsBySarah: Int = 0, numNewPostsBySanjo: Int = 0,
          lastPostBy: UserId,
          lastPostCheckFn: Post => Unit,
          numNewNotfs: Int,
          newNotfCheckFn: Seq[Notification.NewPost] => Unit = null
          ) {
      val curDump = SitePatchMaker(context).loadSiteDump(site.id)
      curDump.pages.length mustBe (prevSiteDump.pages.length + numNewPages)
      curDump.posts.length mustBe (prevSiteDump.posts.length + numNewPosts)

      val prevPostsByAuthorId = prevSiteDump.posts.groupBy(_.createdById)
      val curPostsByAuthorId = curDump.posts.groupBy(_.createdById)

      {
        def numCurBy(userId: Int) = curPostsByAuthorId.getOrElse(userId, Nil).length
        def numPrevBy(userId: Int) = prevPostsByAuthorId.getOrElse(userId, Nil).length
        numCurBy(alice.id) mustBe (numPrevBy(alice.id) + numNewPostsByAlice)
        numCurBy(bob.id)   mustBe (numPrevBy(bob.id)   + numNewPostsByBob)
        numCurBy(sarah.id) mustBe (numPrevBy(sarah.id) + numNewPostsBySarah)
        numCurBy(sanjo.id) mustBe (numPrevBy(sanjo.id) + numNewPostsBySanjo)
      }

      val lastPost = curDump.posts.last
      lastPost.createdById mustBe lastPostBy
      lastPost.currentRevisionById mustBe lastPostBy
      lastPost.parentNr mustBe None
      lastPost.tyype mustBe PostType.ChatMessage
      lastPost.currentRevLastEditedAt mustBe None
      lastPost.currentRevSourcePatch mustBe None
      lastPost.isCurrentVersionApproved mustBe true
      lastPost.approvedById mustBe Some(SysbotUserId)
      lastPost.safeRevisionNr mustBe None  // not reviewed by a human
      lastPost.approvedRevisionNr mustBe Some(1)
      lastPostCheckFn(lastPost)

      curDump.notifications.length mustBe (prevSiteDump.notifications.length + numNewNotfs)
      if (numNewNotfs > 0) {
        newNotfCheckFn(
            curDump.notifications.takeRight(numNewNotfs)
              .asInstanceOf[Seq[Notification.NewPost]])
      }

      prevSiteDump = curDump
    }


    lazy val bobSaysHiAliceMessage = aliceSaysHiBobMessage.copy(
      extId = "bobSaysHiAliceMessage-ext-id",
      authorRef = bob.extIdAsRef.get,
      body = "Yes Bob is my name. How did you know? ... Mind reading? " +
        "But I wasn't thinking about my name")

    "Bob replies" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        postPatches = Vector(bobSaysHiAliceMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 7039067258" in {
      var bobsPost: Post = null
      checkChanges(
        // Bob's reply:
        numNewPosts = 1,
        numNewPostsByBob = 1,
        lastPostBy = bob.id,
        lastPostCheckFn = (post: Post) => {
          bobsPost = post
          post.currentSource mustBe bobSaysHiAliceMessage.body
        },
        // A notf to Alice:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe bob.id
          notf.toUserId mustBe alice.id
          notf.tyype mustBe NotificationType.NewPost
          notf.uniquePostId mustBe bobsPost.id
        })
    }


    // Can have two chats going on in parallel

    lazy val sarahSaysHiSanjoMessage = makeChatMessage(
      extId = "sarahSaysHiSanjoMessage ext id",
      chatPagePatch2,
      sarah,
      "Hi Sanjo, how was school today? What's one plus one?")

    "Sarah and Sanjo starts another discussion in parallel" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        pagePatches = Vector(chatPagePatch2),
        postPatches = Vector(sarahSaysHiSanjoMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 40692463" in {
      checkChanges(
        // Sarah's message:
        numNewPages = 1,        // The message topic
        numNewPosts =  3,       // title, body, message
        numNewPostsBySarah = 3, //  —""—
        lastPostBy = sarah.id,
        lastPostCheckFn = (post: Post) => {
          post.currentSource mustBe sarahSaysHiSanjoMessage.body
        },
        // A notf to Sanjo:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe sarah.id
          notf.toUserId mustBe sanjo.id
          //notf.tyype mustBe NotificationType.Message
        })
    }


    lazy val sanjoRelpiesMessage = makeChatMessage(
      extId = "sanjoRelpiesMessage ext id",
      chatPagePatch2,
      sanjo,
      "One plus one, like in one snowball plus one snowball equals how many snowballs?")

    "Sanjo replies, page *is* included in patch (55294962)" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        pagePatches = Vector(chatPagePatch2),
        postPatches = Vector(sanjoRelpiesMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 5029642" in {
      checkChanges(
        // Sanjo's reply:
        numNewPosts = 1,
        numNewPostsBySanjo = 1,
        lastPostBy = sanjo.id,
        lastPostCheckFn = (post: Post) => {
          post.currentSource mustBe sanjoRelpiesMessage.body
        },
        // A notf to Sarah:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe sanjo.id
          notf.toUserId mustBe sarah.id
          notf.tyype mustBe NotificationType.NewPost
        })
    }


    /* Edits not yet implemented. Would be simpler with ActionPatch? [ACTNPATCH]

    lazy val sanjosEditedReply = sanjoRelpiesMessage.copy(
      body = sanjoRelpiesMessage.body + "\n" +
        "Or one rabbit plus one fox equals how many rabbits?\n" +
        "Or one rabbit plus one rabbit equals how many rabbits?\n")

    "Sanjo edits her reply — page included in patch" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        postPatches = Vector(sanjosEditedReply))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 505098256" in {
      checkChanges(
        numNewPosts = 0,
        lastPostBy = sanjo.id,
        lastPostCheckFn = (post: Post) => {
          post.currentSource mustBe sanjosEditedReply.body
        },
        numNewNotfs = 0)
    }


    lazy val sanjosEditedReply2 = sanjoRelpiesMessage.copy(
      body = sanjosEditedReply.body +
        "Or one snowball plus a sunny day becomes how many snowballs?")

    "Sanjo edits her reply again — page *not* included in patch" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        postPatches = Vector(sanjosEditedReply2))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 59088512019409653" in {
      checkChanges(
        numNewPosts = 0,
        lastPostBy = sanjo.id,
        lastPostCheckFn = (post: Post) => {
          post.currentSource mustBe sanjosEditedReply2.body
        },
        numNewNotfs = 0)
    } */


    lazy val sarahRepliesMessage = makeChatMessage(
      extId = "sarahRepliesMessage ~., ext #!?%~$ id",
      chatPagePatch2,
      sarah,
      "Sanjo, I'm older than you, and must know better")

    "Sarah replies, page is *not* included in patch (55294962)" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        postPatches = Vector(sarahRepliesMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 59028906" in {
      checkChanges(
        // Sarah's reply to Sanjo's reply:
        numNewPosts = 1,
        numNewPostsBySarah = 1,
        lastPostBy = sarah.id,
        lastPostCheckFn = (post: Post) => {
          post.currentSource mustBe sarahRepliesMessage.body
        },
        // A notf to Sanjo:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe sarah.id
          notf.toUserId mustBe sanjo.id
          notf.tyype mustBe NotificationType.NewPost
        })
    }


    // Back on the 1st chat page again

    lazy val aliceRepliesToBobMessage = aliceSaysHiBobMessage.copy(
      extId = "aliceRepliesToBobMessage-ext-id",
      body = "Bob, I call everyone Bob")

    "Alice replies to Bob" in {
      val simplePatch = SimpleSitePatch(
        upsertOptions = Some(UpsertOptions(sendNotifications = Some(true))),
        postPatches = Vector(aliceRepliesToBobMessage))
      upsertSimplePatch(simplePatch, siteDao)
    }

    "... check the changes 40649026434" in {
      checkChanges(
        numNewPosts = 1,         // Alice's reply to Bob
        numNewPostsByAlice = 1,  //  – "" —
        lastPostBy = alice.id,
        lastPostCheckFn = (alicesPost: Post) => {
          alicesPost.currentSource mustBe aliceRepliesToBobMessage.body
        },
        // One notf to Bob about Alice's message:
        numNewNotfs = 1,
        newNotfCheckFn = { case Seq(notf) =>
          notf.byUserId mustBe alice.id
          notf.toUserId mustBe bob.id
          notf.tyype mustBe NotificationType.NewPost
        })
    }

  }

}
