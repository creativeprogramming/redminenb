/*
 * Copyright 2012 Anchialas.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kenai.redmineNB.query;

import com.kenai.redmineNB.Redmine;
import com.kenai.redmineNB.RedmineConnector;
import com.kenai.redmineNB.issue.RedmineIssue;
import com.kenai.redmineNB.issue.RedmineIssueNode;
import com.kenai.redmineNB.repository.RedmineRepository;
import com.kenai.redmineNB.util.RedmineUtil;

import com.taskadapter.redmineapi.AuthenticationException;
import com.taskadapter.redmineapi.NotFoundException;
import com.taskadapter.redmineapi.RedmineException;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.apache.commons.lang.StringUtils;
import org.netbeans.modules.bugtracking.api.Issue;
import org.netbeans.modules.bugtracking.issuetable.ColumnDescriptor;
import org.netbeans.modules.bugtracking.spi.QueryProvider;
import org.netbeans.modules.bugtracking.ui.issue.cache.IssueCache;
import org.netbeans.modules.bugtracking.util.LogUtils;
import org.openide.nodes.Node.Property;
import org.openide.util.Exceptions;

/**
 * Redmine Query.
 *
 * @author Anchialas <anchialas@gmail.com>
 */
public final class RedmineQuery {

   private String name;
   private final RedmineRepository repository;
   private final Set<String> issues;
   //
   //private String urlParameters;
   private boolean initialUrlDef;
   private boolean firstRun = true;
   private boolean saved;
   protected long lastRefresh;
   private final PropertyChangeSupport support;
   //
   private RedmineQueryController queryController;
   private ColumnDescriptor[] columnDescriptors;

   public RedmineQuery(RedmineRepository repository) {
      this(null, repository, null, false, false, true);
   }

   public RedmineQuery(String name, RedmineRepository repository, String urlParameters,
                       boolean saved, boolean urlDef, boolean initControler) {
      this.name = name;
      this.repository = repository;
      this.saved = saved;
      //this.urlParameters = urlParameters;
      this.initialUrlDef = urlDef;
      this.lastRefresh = repository.getIssueCache().getQueryTimestamp(getStoredQueryName());
      this.issues = new HashSet<String>();
      this.support = new PropertyChangeSupport(this);
   }

   public void addPropertyChangeListener(PropertyChangeListener listener) {
      support.addPropertyChangeListener(listener);
   }

   public void removePropertyChangeListener(PropertyChangeListener listener) {
      support.removePropertyChangeListener(listener);
   }

   private void fireQuerySaved() {
      support.firePropertyChange(QueryProvider.EVENT_QUERY_SAVED, null, null);
   }

   private void fireQueryRemoved() {
      support.firePropertyChange(QueryProvider.EVENT_QUERY_REMOVED, null, null);
   }

   private void fireQueryIssuesChanged() {
      support.firePropertyChange(QueryProvider.EVENT_QUERY_ISSUES_CHANGED, null, null);
   }

   public String getDisplayName() {
      return name;
   }

   public String getTooltip() {
      return name + " - " + repository.getDisplayName(); // NOI18N
   }

   public synchronized RedmineQueryController getController() {
      if (queryController == null) {
         queryController = new RedmineQueryController(repository, this);
      }
      return queryController;
   }

   public RedmineRepository getRepository() {
      return repository;
   }

   public ColumnDescriptor[] getColumnDescriptors() {
      if (columnDescriptors == null) {
         Property<?>[] props = new RedmineIssueNode(new RedmineIssue(repository)).getProperties();
         columnDescriptors = new ColumnDescriptor[props.length];
         for (int i = 0; i < props.length; i++) {
            Property<?> p = props[i];
            columnDescriptors[i] = RedmineUtil.convertNodePropertyToColumnDescriptor(p);
         }
      }
      return columnDescriptors;
   }

   void refresh(boolean autoReresh) {
      doRefresh(autoReresh);
   }

   public void refresh() { // XXX what if already running! - cancel task
      doRefresh(false);
   }

   private boolean doRefresh(final boolean autoRefresh) {
      // XXX what if already running! - cancel task
      //assert !SwingUtilities.isEventDispatchThread() : "Accessing remote host. Do not call in awt"; // NOI18N

      final boolean ret[] = new boolean[1];
      executeQuery(new Runnable() {
         @Override
         public void run() {
            Redmine.LOG.log(Level.FINE, "refresh start - {0}", name); // NOI18N
            try {

               // keeps all issues we will retrieve from the server
               // - those matching the query criteria
               // - and the obsolete ones
               Set<String> queryIssues = new HashSet<String>();

               queryController.getIssueTable().started();
               issues.clear();
//                    archivedIssues.clear();
               if (isSaved()) {
                  // read the stored state ...
                  queryIssues.addAll(repository.getIssueCache().readQueryIssues(getStoredQueryName()));
                  queryIssues.addAll(repository.getIssueCache().readArchivedQueryIssues(getStoredQueryName()));
                  // ... and they might be rendered obsolete if not returned by the query
//                        archivedIssues.addAll(queryIssues);
               }
               firstRun = false;
               try {
                  List<com.taskadapter.redmineapi.bean.Issue> issueArr = doSearch(queryController.getSearchParameters());
                  for (com.taskadapter.redmineapi.bean.Issue issue : issueArr) {
                     getController().addProgressUnit(RedmineIssue.getDisplayName(issue));
                     try {
                        RedmineIssue redmineIssue = repository.getIssueCache().setIssueData(String.valueOf(issue.getId()), issue);
                        issues.add(redmineIssue.getID());

                        fireNotifyData(redmineIssue); // XXX - !!! triggers getIssues()

                     } catch (IOException ex) {
                        Redmine.LOG.log(Level.SEVERE, null, ex);
                        return;
                     }
                  }

               } catch (Exception e) {
                  Exceptions.printStackTrace(e);
               }

               // only issues not returned by the query are obsolete
               //archivedIssues.removeAll(issues);
               if (isSaved()) {
                  // ... and store all issues you got
                  repository.getIssueCache().storeQueryIssues(getStoredQueryName(), issues.toArray(new String[issues.size()]));
                  //repository.getIssueCache().storeArchivedQueryIssues(getStoredQueryName(), archivedIssues.toArray(new String[0]));
               }

               // now get the task data for
               // - all issue returned by the query
               // - and issues which were returned by some previous run and are archived now
               queryIssues.addAll(issues);

               getController().switchToDeterminateProgress(queryIssues.size());

            } finally {
               logQueryEvent(issues.size(), autoRefresh);
               Redmine.LOG.log(Level.FINE, "refresh finish - {0}", name); // NOI18N
            }
         }
      });

      return ret[0];
   }

   public final String getStoredQueryName() {
      return getDisplayName();
   }

   protected void logQueryEvent(int count, boolean autoRefresh) {
      LogUtils.logQueryEvent(RedmineConnector.getConnectorName(),
                             name,
                             count,
                             false,
                             autoRefresh);
   }

   /**
    * Performs the issue search with the attributes and values provided by the map.
    * <p>
    * Note: The Redmine REST API does not support full search support for all
    * fields. So the issues are post filtered here.
    *
    * @see http://www.redmine.org/projects/redmine/wiki/Rest_Issues
    * @see RedmineQueryController#RedmineQueryController
    * @param searchParameters
    */
   private List<com.taskadapter.redmineapi.bean.Issue> doSearch(Map<String, RedmineQueryParameter> searchParameters)
           throws IOException, AuthenticationException, NotFoundException, RedmineException {

      boolean searchSubject = false;
      boolean searchDescription = false;
      boolean searchComments = false;
      RedmineQueryParameter queryStringParameter = searchParameters.remove("query");
      Set<RedmineQueryParameter> multiValueParameters = new HashSet<RedmineQueryParameter>();

      Map<String, String> m = new HashMap<String, String>();
      m.put("project_id", String.valueOf(repository.getProject().getId()));

      for (RedmineQueryParameter p : searchParameters.values()) {
         if (StringUtils.isNotBlank(p.getValueString())) {
            ParameterValue[] paramValues = p.getValues();
            if (paramValues.length == 1) {
               if ("is_subject".equals(p.getParameter())) {
                  searchSubject = "1".equals(paramValues[0].getValue());
               } else if ("is_description".equals(p.getParameter())) {
                  searchDescription = "1".equals(paramValues[0].getValue());
               } else if ("is_comments".equals(p.getParameter())) {
                  searchComments = "1".equals(paramValues[0].getValue());
               } else {
                  m.put(p.getParameter(), paramValues[0].getValue());
               }
            } else if (paramValues.length > 1) {
               multiValueParameters.add(p);
            }
         }
      }

      // Perform search
      List<com.taskadapter.redmineapi.bean.Issue> issueArr = repository.getManager().getIssues(m);

      // Post filtering: Query string
      if (queryStringParameter != null && !queryStringParameter.isEmpty()
              && (searchSubject || searchDescription || searchComments)) {
         String queryStr = queryStringParameter.getValueString();

         List<com.taskadapter.redmineapi.bean.Issue> newArr = new ArrayList<com.taskadapter.redmineapi.bean.Issue>(issueArr.size());
         for (com.taskadapter.redmineapi.bean.Issue issue : issueArr) {
            if ((searchSubject && StringUtils.containsIgnoreCase(issue.getSubject(), queryStr))
                    || (searchDescription && StringUtils.containsIgnoreCase(issue.getDescription(), queryStr)) /*
                     || (searchComments && StringUtils.containsIgnoreCase(..., queryStr))
                     */) {
               newArr.add(issue);
            }
         }
         issueArr = newArr;
      }

      // Post filtering: Multi-value parameters
      if (!multiValueParameters.isEmpty()) {
         List<com.taskadapter.redmineapi.bean.Issue> newArr = new ArrayList<com.taskadapter.redmineapi.bean.Issue>(issueArr.size());
         for (com.taskadapter.redmineapi.bean.Issue issue : issueArr) {
            for (RedmineQueryParameter p : multiValueParameters) {
               // RedmineIssue.getFieldValue(RedmineIssue.FIELD_xxx)
               // TODO: map FIELD_xxx property to query parameter
               String paramName = p.getParameter();
               if ("tracker_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if (String.valueOf(issue.getTracker().getId()).equals(pv.getValue())) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else if ("status_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if (String.valueOf(issue.getStatusId()).equals(pv.getValue())) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else if ("priority_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if (String.valueOf(issue.getPriorityId()).equals(pv.getValue())) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else if ("assigned_to_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if ((pv == ParameterValue.NONE_PARAMETERVALUE && issue.getAssignee() == null)
                             || (issue.getAssignee() != null && String.valueOf(issue.getAssignee().getId()).equals(pv.getValue()))) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else if ("category_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if ((pv == ParameterValue.NONE_PARAMETERVALUE && issue.getCategory() == null)
                             || (issue.getCategory() != null && String.valueOf(issue.getCategory().getId()).equals(pv.getValue()))) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else if ("fixed_version_id".equals(paramName)) {
                  for (ParameterValue pv : p.getValues()) {
                     if ((pv == ParameterValue.NONE_PARAMETERVALUE && issue.getTargetVersion() == null)
                             || (issue.getTargetVersion() != null && String.valueOf(issue.getTargetVersion().getId()).equals(pv.getValue()))) {
                        newArr.add(issue);
                        break;
                     }
                  }
               } else {
                  Redmine.LOG.log(Level.WARNING, "Unsupported multi-value parameter ''{0}''", paramName);
               }

            }
         }
         issueArr = newArr;
      }

      return issueArr;
   }

   public void remove() {
      repository.removeQuery(this);
      fireQueryRemoved();
   }

   public boolean contains(String id) {
      return issues.contains(id);
   }

   boolean wasRun() {
      return !firstRun;
   }

   long getLastRefresh() {
      return lastRefresh;
   }
   //
//   public String getUrlParameters() {
//      return urlParameters;
//   }
//

   public void setName(String name) {
      this.name = name;
   }

   public void setSaved(boolean saved) {
      this.saved = saved;
      fireQuerySaved();
   }

   public boolean isSaved() {
      return saved;
   }

   public Collection<RedmineIssue> getIssues() {
      return getIssues(~0);
   }

   public Collection<RedmineIssue> getIssues(int includeStatus) {
      if (issues == null) {
         return Collections.<RedmineIssue>emptyList();
      }
      List<String> ids = new ArrayList<String>();
      synchronized (issues) {
         ids.addAll(issues);
      }
      IssueCache<RedmineIssue, com.taskadapter.redmineapi.bean.Issue> cache = repository.getIssueCache();
      List<RedmineIssue> ret = new ArrayList<RedmineIssue>();
      for (String id : ids) {
         int status = getIssueStatus(id);
         if ((status & includeStatus) != 0) {
            ret.add(cache.getIssue(id));
         }
      }
      return ret;
   }

   public boolean contains(Issue issue) {
      return issues.contains(issue.getID());
   }

   public int getIssueStatus(Issue issue) {
      return getIssueStatus(issue.getID());
   }

   public int getIssueStatus(String id) {
      return repository.getIssueCache().getStatus(id);
   }

   public void addNotifyListener(QueryNotifyListener l) {
      List<QueryNotifyListener> list = getNotifyListeners();
      synchronized (list) {
         list.add(l);
      }
   }

   public void removeNotifyListener(QueryNotifyListener l) {
      List<QueryNotifyListener> list = getNotifyListeners();
      synchronized (list) {
         list.remove(l);
      }
   }

   protected void fireNotifyData(RedmineIssue issue) {
      QueryNotifyListener[] listeners = getListeners();
      for (QueryNotifyListener l : listeners) {
         l.notifyData(issue);
      }
   }

   protected void fireStarted() {
      QueryNotifyListener[] listeners = getListeners();
      for (QueryNotifyListener l : listeners) {
         l.started();
      }
   }

   protected void fireFinished() {
      QueryNotifyListener[] listeners = getListeners();
      for (QueryNotifyListener l : listeners) {
         l.finished();
      }
   }

   // XXX move to API
   protected void executeQuery(Runnable r) {
      fireStarted();
      try {
         r.run();
      } finally {
         fireFinished();
         fireQueryIssuesChanged();
         lastRefresh = System.currentTimeMillis();
      }
   }

   private QueryNotifyListener[] getListeners() {
      List<QueryNotifyListener> list = getNotifyListeners();
      QueryNotifyListener[] listeners;
      synchronized (list) {
         listeners = list.toArray(new QueryNotifyListener[list.size()]);
      }
      return listeners;
   }
   private List<QueryNotifyListener> notifyListeners;

   private List<QueryNotifyListener> getNotifyListeners() {
      if (notifyListeners == null) {
         notifyListeners = new ArrayList<QueryNotifyListener>();
      }
      return notifyListeners;
   }

   public String getUrlParameters() {
      throw new UnsupportedOperationException("Not yet implemented");
   }
}
