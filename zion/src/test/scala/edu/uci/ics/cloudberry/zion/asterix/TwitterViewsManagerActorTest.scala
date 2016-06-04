package edu.uci.ics.cloudberry.zion.asterix

import edu.uci.ics.cloudberry.zion.actor.{TestUtil, ViewMetaRecord}
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TwitterViewsManagerActorTest extends Specification with Mockito {

  val mockConn = mock[AsterixConnection]
  when(mockConn.post(any[String])).thenAnswer(new Answer[Future[String]] {
    override def answer(invocation: InvocationOnMock): Future[String] = {
      Future {
        invocation.getArguments.head.asInstanceOf[String]
      }
    }
  })

  val viewMetaR1 = ViewMetaRecord("twitter", "twitter_", TwitterCountyDaySummaryView.SummaryLevel,
                                  new DateTime(0), new DateTime(5000), new DateTime(4000), 0, 1 hours)
  val viewMetaR2 = viewMetaR1.copy(viewKey = "twitter_trump")
  val viewMetaR3 = viewMetaR1.copy(viewKey = "twitter_rain", visitTimes = 20)
  val testRecords = Seq[ViewMetaRecord](viewMetaR1, viewMetaR2, viewMetaR3)

  "A TwitterViewsManagerActor" should {
    "load meta store after start " in {
      ok
    }
    "create a store if it is a new view " in {
      ok
    }
    "forward the query to children viewActor " in {
      ok
    }
    "update the meta store when reviece the request" in {
      ok
    }
  }

  "TwitterViewsManagerActorTest Static Functions" should {
    "flushMetaToStore" in {
      val faql = TwitterViewsManagerActor.flushMetaToStore(mockConn, testRecords)
      Await.result(faql.mapTo[String], 100 millisecond).trim must_== (
        """
          |use dataverse twitter
          |create type typeViewMeta2 if not exists as open {
          |  sourceName: string,
          |  viewKey: string
          |}
          |create dataset twitter.viewMeta (typeViewMeta2) if not exists primary key viewKey
          |
          |upsert into dataset twitter.viewMeta (
          |[ {
          |  "sourceName" : "twitter",
          |  "viewKey" : "twitter_",
          |  "summaryLevel" : {
          |    "spatialLevel" : 1,
          |    "timeLevel" : 3
          |  },
          |  "startTime" : "1969-12-31T16:00:00.000Z",
          |  "lastVisitTime" : "1969-12-31T16:00:05.000Z",
          |  "lastUpdateTime" : "1969-12-31T16:00:04.000Z",
          |  "visitTimes" : 0,
          |  "updateCycle" : 3600
          |}, {
          |  "sourceName" : "twitter",
          |  "viewKey" : "twitter_trump",
          |  "summaryLevel" : {
          |    "spatialLevel" : 1,
          |    "timeLevel" : 3
          |  },
          |  "startTime" : "1969-12-31T16:00:00.000Z",
          |  "lastVisitTime" : "1969-12-31T16:00:05.000Z",
          |  "lastUpdateTime" : "1969-12-31T16:00:04.000Z",
          |  "visitTimes" : 0,
          |  "updateCycle" : 3600
          |}, {
          |  "sourceName" : "twitter",
          |  "viewKey" : "twitter_rain",
          |  "summaryLevel" : {
          |    "spatialLevel" : 1,
          |    "timeLevel" : 3
          |  },
          |  "startTime" : "1969-12-31T16:00:00.000Z",
          |  "lastVisitTime" : "1969-12-31T16:00:05.000Z",
          |  "lastUpdateTime" : "1969-12-31T16:00:04.000Z",
          |  "visitTimes" : 20,
          |  "updateCycle" : 3600
          |} ]
          |);
        """.stripMargin.trim)
    }

    "generateSummaryViewAQL" in {
      val str = TwitterViewsManagerActor.generateSummaryViewAQL("ds_tweet", TwitterCountyDaySummaryView.SummaryLevel)
      str.trim must_== ("""
                          |use dataverse twitter
                          |drop dataset ds_tweet_ if exists
                          |
                          |create type autoType if not exists as open {
                          |  id: uuid
                          |}
                          |create dataset ds_tweet_(autoType) if not exists primary key id autogenerated;
                          |
                          |insert into dataset ds_tweet_
                          |(for $t in dataset ds_tweet
                          |  group by
                          |  $state := $t.geo_tag.stateID,
                          |  $county := $t.geo_tag.countyID,
                          |  $timeBin := interval-bin($t.create_at, datetime("2012-01-01T00:00:00"), day-time-duration("P1D")) with $t
                          |  return {
                          |    "stateID": $state,
                          |    "countyID": $county,
                          |    "timeBin": $timeBin,
                          |    "tweetCount": count($t),
                          |    "retweetCount": count(for $tt in $t where $tt.is_retweet return $tt),
                          |    "users": count(for $tt in $t group by $uid := $tt.user.id with $tt return $uid),
                          |    "topHashTags": (for $tt in $t
                          |                      where not(is-null($tt.hashtags))
                          |                      for $h in $tt.hashtags
                          |                      group by $tag := $h with $h
                          |                      let $c := count($h)
                          |                      order by $c desc
                          |                      limit 50
                          |                      return { "tag": $tag, "count": $c})
                          |  }
                          |)
                          | """.stripMargin.trim)
    }

    "loadFromeMetaStore" in {
      TestUtil.withAsterixConn(Json.toJson(testRecords)) { conn =>
        val faql = TwitterViewsManagerActor.loadFromMetaStore("ds_tweet", conn)
        Await.result(faql, 2 seconds) must_== (testRecords)
      }
    }

    "generateSubSetViewAQL" in {
      val aql = TwitterViewsManagerActor.generateSubSetViewAQL("ds_tweet", "rain")
      aql.trim must_== ("""
                          |use dataverse twitter
                          |drop dataset ds_tweet_rain if exists
                          |create dataset ds_tweet_rain(typeTweet) if not exists primary key "id";
                          |insert into dataset ds_tweet_rain(
                          |for $t in dataset ds_tweet
                          |let $keyword0 := "rain"
                          |where similarity-jaccard(word-tokens($t."text"), word-tokens($keyword0)) > 0.0
                          |return $t)
                          | """.stripMargin.trim)
    }
  }
}
