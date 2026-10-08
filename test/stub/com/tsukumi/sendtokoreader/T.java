package com.tsukumi.sendtokoreader;
import java.io.*;
public class T { public static void main(String[] a) throws Exception {
  System.out.println(WebDav.encodePath("http://127.0.0.1:8765/books/电子书"));
  WebDav bad = new WebDav("http://127.0.0.1:8765/books/电子书/", "test", "wrong");
  System.out.println("bad auth: " + bad.checkFolder());
  WebDav d = new WebDav("http://127.0.0.1:8765/books/电子书/", "test", "test-pass");
  System.out.println("check: " + d.checkFolder());
  byte[] data = new byte[300000]; new java.util.Random(1).nextBytes(data);
  System.out.println("put before mkcol: " + d.put("x.epub", new ByteArrayInputStream(data), data.length, s->{}));
  System.out.println("mkcol: " + d.createFolder() + " check: " + d.checkFolder());
  System.out.println("put fixed: " + d.put("诡秘之主 - 爱潜水的乌贼 #1+2.epub", new ByteArrayInputStream(data), data.length, s->{}));
  System.out.println("put chunked: " + d.put("百年 孤独.pdf", new ByteArrayInputStream(data), -1, s->{}));
}}
