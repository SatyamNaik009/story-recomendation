package com.storystylist.backend.storage;
public class StorageException extends RuntimeException {
 private final String code; private final int status;
 public StorageException(String code,String message,int status){super(message);this.code=code;this.status=status;}
 public String code(){return code;} public int status(){return status;}
 public static StorageException corrupt(){return new StorageException("STORED_STORY_INVALID","Stored story data is missing or inconsistent",500);}
 public static StorageException database(RuntimeException failure){
  for(Throwable c=failure;c!=null;c=c.getCause()) {
   if(c instanceof org.springframework.dao.DataAccessResourceFailureException || c instanceof org.springframework.dao.TransientDataAccessException
      || c instanceof org.springframework.transaction.CannotCreateTransactionException || c instanceof java.sql.SQLTransientException
      || c instanceof java.sql.SQLNonTransientConnectionException)
    return new StorageException("STORAGE_UNAVAILABLE","Temporary story storage is unavailable",503);
  }
  return new StorageException("STORAGE_FAILED","Unable to complete story storage operation",500);
 }
}
