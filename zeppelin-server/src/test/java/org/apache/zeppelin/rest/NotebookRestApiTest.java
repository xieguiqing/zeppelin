/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zeppelin.rest;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
import com.google.gson.reflect.TypeToken;

import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.zeppelin.notebook.Notebook;
import org.apache.zeppelin.socket.NotebookServer;
import org.apache.zeppelin.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.notebook.Note;
import org.apache.zeppelin.notebook.Paragraph;
import org.apache.zeppelin.scheduler.Job;
import org.apache.zeppelin.user.AuthenticationInfo;

/**
 * Zeppelin notebook rest api tests.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class NotebookRestApiTest extends AbstractTestRestApi {
  Gson gson = new Gson();
  AuthenticationInfo anonymous;

  @BeforeClass
  public static void init() throws Exception {
    startUp(NotebookRestApiTest.class.getSimpleName());
    TestUtils.getInstance(Notebook.class).setParagraphJobListener(NotebookServer.getInstance());
  }

  @AfterClass
  public static void destroy() throws Exception {
    AbstractTestRestApi.shutDown();
  }

  @Before
  public void setUp() {
    anonymous = new AuthenticationInfo("anonymous");
  }

  @Test
  public void testGetNoteParagraphJobStatus() throws IOException {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);

      String paragraphId = note1.getLastParagraph().getId();

      GetMethod get = httpGet("/notebook/job/" + note1.getId() + "/" + paragraphId);
      assertThat(get, isAllowed());
      Map<String, Object> resp = gson.fromJson(get.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      Map<String, Set<String>> paragraphStatus = (Map<String, Set<String>>) resp.get("body");

      // Check id and status have proper value
      assertEquals(paragraphStatus.get("id"), paragraphId);
      assertEquals(paragraphStatus.get("status"), "READY");
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }

  @Test
  public void testRunParagraphJob() throws IOException {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);

      Paragraph p = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);

      // run blank paragraph
      PostMethod post = httpPost("/notebook/job/" + note1.getId() + "/" + p.getId(), "");
      assertThat(post, isAllowed());
      Map<String, Object> resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "OK");
      post.releaseConnection();
      assertEquals(p.getStatus(), Job.Status.FINISHED);

      // run non-blank paragraph
      p.setText("test");
      post = httpPost("/notebook/job/" + note1.getId() + "/" + p.getId(), "");
      assertThat(post, isAllowed());
      resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "OK");
      post.releaseConnection();
      assertNotEquals(p.getStatus(), Job.Status.READY);
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }

  @Test
  public void testRunParagraphSynchronously() throws IOException {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);

      Paragraph p = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);

      // run non-blank paragraph
      String title = "title";
      String text = "%sh\n sleep 1";
      p.setTitle(title);
      p.setText(text);

      PostMethod post = httpPost("/notebook/run/" + note1.getId() + "/" + p.getId(), "");
      assertThat(post, isAllowed());
      Map<String, Object> resp = gson.fromJson(post.getResponseBodyAsString(),
          new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "OK");
      post.releaseConnection();
      assertNotEquals(p.getStatus(), Job.Status.READY);

      // Check if the paragraph is emptied
      assertEquals(title, p.getTitle());
      assertEquals(text, p.getText());

      // run invalid code
      text = "%sh\n invalid_cmd";
      p.setTitle(title);
      p.setText(text);

      post = httpPost("/notebook/run/" + note1.getId() + "/" + p.getId(), "");
      assertEquals(500, post.getStatusCode());
      resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals("INTERNAL_SERVER_ERROR", resp.get("status"));
      StringMap stringMap = (StringMap) resp.get("body");
      assertEquals("ERROR", stringMap.get("code"));
      List<StringMap> interpreterResults = (List<StringMap>) stringMap.get("msg");
      assertTrue(interpreterResults.get(0).toString(),
              interpreterResults.get(0).get("data").toString().contains("invalid_cmd: command not found"));
      post.releaseConnection();
      assertNotEquals(p.getStatus(), Job.Status.READY);

      // Check if the paragraph is emptied
      assertEquals(title, p.getTitle());
      assertEquals(text, p.getText());
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }

  @Test
  public void testRunAllParagraph_AllSuccess() throws IOException {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      // 2 paragraphs
      // P1:
      //    %python
      //    import time
      //    time.sleep(1)
      //    user='abc'
      // P2:
      //    %python
      //    from __future__ import print_function
      //    print(user)
      //
      Paragraph p1 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      Paragraph p2 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      p1.setText("%python import time\ntime.sleep(1)\nuser='abc'");
      p2.setText("%python from __future__ import print_function\nprint(user)");

      PostMethod post = httpPost("/notebook/job/" + note1.getId(), "");
      assertThat(post, isAllowed());
      Map<String, Object> resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "OK");
      post.releaseConnection();

      assertEquals(Job.Status.FINISHED, p1.getStatus());
      assertEquals(Job.Status.FINISHED, p2.getStatus());
      assertEquals("abc\n", p2.getReturn().message().get(0).getData());
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }

  @Test
  public void testRunAllParagraph_FirstFailed() throws IOException {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      // 2 paragraphs
      // P1:
      //    %python
      //    import time
      //    time.sleep(1)
      //    from __future__ import print_function
      //    print(user)
      // P2:
      //    %python
      //    user='abc'
      //
      Paragraph p1 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      Paragraph p2 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      p1.setText("%python import time\ntime.sleep(1)\nfrom __future__ import print_function\nprint(user2)");
      p2.setText("%python user2='abc'\nprint(user2)");

      PostMethod post = httpPost("/notebook/job/" + note1.getId(), "");
      assertThat(post, isExpectationFailed());
      Map<String, Object> resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "EXPECTATION_FAILED");
      assertTrue(resp.get("message").toString().contains("Fail to run note because paragraph"));
      post.releaseConnection();

      assertEquals(Job.Status.ERROR, p1.getStatus());
      // p2 will be skipped because p1 is failed.
      assertEquals(Job.Status.READY, p2.getStatus());
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }

  @Test
  public void testCloneNote() throws IOException {
    Note note1 = null;
    String clonedNoteId = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      PostMethod post = httpPost("/notebook/" + note1.getId(), "");
      LOG.info("testCloneNote response\n" + post.getResponseBodyAsString());
      assertThat(post, isAllowed());
      Map<String, Object> resp = gson.fromJson(post.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      clonedNoteId = (String) resp.get("body");
      post.releaseConnection();

      GetMethod get = httpGet("/notebook/" + clonedNoteId);
      assertThat(get, isAllowed());
      Map<String, Object> resp2 = gson.fromJson(get.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      Map<String, Object> resp2Body = (Map<String, Object>) resp2.get("body");

      //    assertEquals(resp2Body.get("name"), "Note " + clonedNoteId);
      get.releaseConnection();
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
      if (null != clonedNoteId) {
        TestUtils.getInstance(Notebook.class).removeNote(clonedNoteId, anonymous);
      }
    }
  }

  @Test
  public void testRenameNote() throws IOException {
    Note note = null;
    try {
      String oldName = "old_name";
      note = TestUtils.getInstance(Notebook.class).createNote(oldName, anonymous);
      assertEquals(note.getName(), oldName);
      String noteId = note.getId();

      final String newName = "testName";
      String jsonRequest = "{\"name\": " + newName + "}";

      PutMethod put = httpPut("/notebook/" + noteId + "/rename/", jsonRequest);
      assertThat("test testRenameNote:", put, isAllowed());
      put.releaseConnection();

      assertEquals(note.getName(), newName);
    } finally {
      // cleanup
      if (null != note) {
        TestUtils.getInstance(Notebook.class).removeNote(note.getId(), anonymous);
      }
    }
  }

  @Test
  public void testUpdateParagraphConfig() throws IOException {
    Note note = null;
    try {
      note = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      String noteId = note.getId();
      Paragraph p = note.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      assertNull(p.getConfig().get("colWidth"));
      String paragraphId = p.getId();
      String jsonRequest = "{\"colWidth\": 6.0}";

      PutMethod put = httpPut("/notebook/" + noteId + "/paragraph/" + paragraphId + "/config",
              jsonRequest);
      assertThat("test testUpdateParagraphConfig:", put, isAllowed());

      Map<String, Object> resp = gson.fromJson(put.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      Map<String, Object> respBody = (Map<String, Object>) resp.get("body");
      Map<String, Object> config = (Map<String, Object>) respBody.get("config");
      put.releaseConnection();

      assertEquals(config.get("colWidth"), 6.0);
      note = TestUtils.getInstance(Notebook.class).getNote(noteId);
      assertEquals(note.getParagraph(paragraphId).getConfig().get("colWidth"), 6.0);
    } finally {
      // cleanup
      if (null != note) {
        TestUtils.getInstance(Notebook.class).removeNote(note.getId(), anonymous);
      }
    }
  }

  @Test
  public void testClearAllParagraphOutput() throws IOException {
    Note note = null;
    try {
      // Create note and set result explicitly
      note = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      Paragraph p1 = note.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      InterpreterResult result = new InterpreterResult(InterpreterResult.Code.SUCCESS,
              InterpreterResult.Type.TEXT, "result");
      p1.setResult(result);

      Paragraph p2 = note.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      p2.setReturn(result, new Throwable());

      // clear paragraph result
      PutMethod put = httpPut("/notebook/" + note.getId() + "/clear", "");
      LOG.info("test clear paragraph output response\n" + put.getResponseBodyAsString());
      assertThat(put, isAllowed());
      put.releaseConnection();

      // check if paragraph results are cleared
      GetMethod get = httpGet("/notebook/" + note.getId() + "/paragraph/" + p1.getId());
      assertThat(get, isAllowed());
      Map<String, Object> resp1 = gson.fromJson(get.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      Map<String, Object> resp1Body = (Map<String, Object>) resp1.get("body");
      assertNull(resp1Body.get("result"));

      get = httpGet("/notebook/" + note.getId() + "/paragraph/" + p2.getId());
      assertThat(get, isAllowed());
      Map<String, Object> resp2 = gson.fromJson(get.getResponseBodyAsString(),
              new TypeToken<Map<String, Object>>() {}.getType());
      Map<String, Object> resp2Body = (Map<String, Object>) resp2.get("body");
      assertNull(resp2Body.get("result"));
      get.releaseConnection();
    } finally {
      // cleanup
      if (null != note) {
        TestUtils.getInstance(Notebook.class).removeNote(note.getId(), anonymous);
      }
    }
  }

  @Test
  public void testRunWithServerRestart() throws Exception {
    Note note1 = null;
    try {
      note1 = TestUtils.getInstance(Notebook.class).createNote("note1", anonymous);
      // 2 paragraphs
      // P1:
      //    %python
      //    import time
      //    time.sleep(1)
      //    from __future__ import print_function
      //    print(user)
      // P2:
      //    %python
      //    user='abc'
      //
      Paragraph p1 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      Paragraph p2 = note1.addNewParagraph(AuthenticationInfo.ANONYMOUS);
      p1.setText("%python import time\ntime.sleep(1)\nuser='abc'");
      p2.setText("%python from __future__ import print_function\nprint(user)");

      PostMethod post1 = httpPost("/notebook/job/" + note1.getId(), "");
      assertThat(post1, isAllowed());
      post1.releaseConnection();
      PutMethod put = httpPut("/notebook/" + note1.getId() + "/clear", "");
      LOG.info("test clear paragraph output response\n" + put.getResponseBodyAsString());
      assertThat(put, isAllowed());
      put.releaseConnection();

      // restart server (while keeping interpreter configuration)
      AbstractTestRestApi.shutDown(false);
      startUp(NotebookRestApiTest.class.getSimpleName(), false);

      note1 = TestUtils.getInstance(Notebook.class).getNote(note1.getId());
      p1 = note1.getParagraph(p1.getId());
      p2 = note1.getParagraph(p2.getId());

      PostMethod post2 = httpPost("/notebook/job/" + note1.getId(), "");
      assertThat(post2, isAllowed());
      Map<String, Object> resp = gson.fromJson(post2.getResponseBodyAsString(),
          new TypeToken<Map<String, Object>>() {}.getType());
      assertEquals(resp.get("status"), "OK");
      post2.releaseConnection();

      assertEquals(Job.Status.FINISHED, p1.getStatus());
      assertEquals(p2.getReturn().toString(), Job.Status.FINISHED, p2.getStatus());
      assertNotNull(p2.getReturn());
      assertEquals("abc\n", p2.getReturn().message().get(0).getData());
    } finally {
      // cleanup
      if (null != note1) {
        TestUtils.getInstance(Notebook.class).removeNote(note1.getId(), anonymous);
      }
    }
  }
}
