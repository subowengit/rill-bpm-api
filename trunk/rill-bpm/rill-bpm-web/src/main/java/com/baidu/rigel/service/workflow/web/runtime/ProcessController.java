package com.baidu.rigel.service.workflow.web.runtime;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.impl.bpmn.behavior.CallActivityBehavior;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.interceptor.Command;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.activiti.engine.impl.util.ReflectUtil;
import org.activiti.engine.impl.util.json.JSONWriter;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import com.baidu.rigel.service.workflow.api.activiti.ActivitiAccessor;
import com.baidu.rigel.service.workflow.api.activiti.bpmndiagram.ProcessMonitorChartInfoHelper;
import com.baidu.rigel.service.workflow.api.activiti.bpmndiagram.ProcessMonitorChartInfoHelper.ChartInfo;
import com.baidu.rigel.service.workflow.api.exception.ProcessException;

@Controller
@RequestMapping("/runtime/process")
public class ProcessController {

	private static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
	@Resource
	private ProcessMonitorChartInfoHelper processMonitorChartInfoHelper;

	public final ProcessMonitorChartInfoHelper getProcessMonitorChartInfoHelper() {
		return processMonitorChartInfoHelper;
	}

	public final void setProcessMonitorChartInfoHelper(
			ProcessMonitorChartInfoHelper processMonitorChartInfoHelper) {
		this.processMonitorChartInfoHelper = processMonitorChartInfoHelper;
	}

	@RequestMapping(value = { "/{processInstanceId}" }, method = RequestMethod.GET)
	public ModelAndView processInstance(@PathVariable String processInstanceId) {

		Map<String, ChartInfo> allChartInfos = processMonitorChartInfoHelper
				.getMonitorChartInfo(processInstanceId);
		if (allChartInfos == null) {
			throw new ProcessException("Can not get process instance ["
					+ processInstanceId + "] chart info, maybe it's has ended.");
		}

		Map<String, List<Integer>> mapArea = new HashMap<String, List<Integer>>();
		for (Entry<String, List<Integer>> entry : allChartInfos
				.get(processInstanceId).getTaskDefinitionKeyPosition()
				.entrySet()) {
			List<Integer> tempList = new ArrayList<Integer>(4);
			tempList.add(entry.getValue().get(0));
			tempList.add(entry.getValue().get(1));
			tempList.add(entry.getValue().get(0) + entry.getValue().get(2));
			tempList.add(entry.getValue().get(1) + entry.getValue().get(3));
			mapArea.put(entry.getKey(), tempList);
		}
		Map<String, Object> charInfoMap = new HashMap<String, Object>();
		charInfoMap.put("taskDefinitionPositionMap", mapArea);
		charInfoMap.put("taskDefinitionTypeMap", allChartInfos.get(processInstanceId).getTaskDefinitionKeyType());
		charInfoMap.put("processInstanceId", processInstanceId);

		ModelAndView mav = new ModelAndView();
		mav.addAllObjects(charInfoMap);
		mav.setViewName("/runtime/process");
		return mav;
	}
	
	@RequestMapping(value = { "/{processInstanceId}/callActivity/{callActivityId}" }, method = RequestMethod.GET)
	public void callActivity(HttpServletRequest request,
			HttpServletResponse response, @PathVariable("processInstanceId") final String processInstanceId,
			@PathVariable("callActivityId") final String callActivityId) throws Exception {
		
		final ActivitiAccessor activitiAccessor = ActivitiAccessor.retrieveActivitiAccessorImpl(
				processMonitorChartInfoHelper.getWorkflowAccessor(), ActivitiAccessor.class);
		
		List<HistoricProcessInstance> callActivityProcessList = activitiAccessor.runExtraCommand(new Command<List<HistoricProcessInstance>>() {

			@Override
			public List<HistoricProcessInstance> execute(CommandContext commandContext) {
				
				final HistoricProcessInstance processInstance = activitiAccessor.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
				
				// Initialize process definition if need.
				ProcessDefinitionImpl pd = null;
				pd = Context.getProcessEngineConfiguration().getDeploymentCache().findDeployedProcessDefinitionById(processInstance.getProcessDefinitionId());
				Assert.notNull(pd, "Can not find process definition of process instance id " + processInstanceId);
				
				ActivityImpl callActivity = pd.findActivity(callActivityId);
				Field callActivityField = ReflectUtil.getField("processDefinitonKey", ((CallActivityBehavior) callActivity.getActivityBehavior()));
				callActivityField.setAccessible(true);
				String processDefinitionKey = ReflectionUtils.getField(callActivityField, ((CallActivityBehavior) callActivity.getActivityBehavior())).toString();
				
				List<HistoricProcessInstance> subProcessList = activitiAccessor.getHistoryService().createHistoricProcessInstanceQuery()
						.superProcessInstanceId(processInstanceId).orderByProcessInstanceStartTime().asc().list();
				if (CollectionUtils.isEmpty(subProcessList)) return null;
				
				List<HistoricProcessInstance> callActivityPidList = new ArrayList<HistoricProcessInstance>(subProcessList.size());
				for (HistoricProcessInstance hp : subProcessList) {
					if (processDefinitionKey.equals(commandContext.getProcessDefinitionManager().findLatestProcessDefinitionById(hp.getProcessDefinitionId()).getKey())) {
						callActivityPidList.add(hp);
					}
				}
				
				return callActivityPidList;
			}
        	
        });
		
		// Return historic task information
		response.setContentType("application/json");
		JSONWriter jsonWriter = new JSONWriter(response.getWriter());
		jsonWriter
			.object()
			.key("callActivityPList")
			.value(callActivityProcessList)
			.endObject();
		response.getWriter().flush();
		
	}

	@RequestMapping(value = { "/{processInstanceId}/diagram" }, method = RequestMethod.GET)
	public void processInstanceDiagram(HttpServletRequest request,
			HttpServletResponse response,
			@PathVariable("processInstanceId") String processInstanceId)
			throws Exception {

		Map<String, ChartInfo> allChartInfos = processMonitorChartInfoHelper
				.getMonitorChartInfo(processInstanceId);
		ChartInfo chartInfo = allChartInfos.get(processInstanceId);
		if (chartInfo == null) {
			throw new ProcessException("Can not get process instance ["
					+ processInstanceId + "] chart info, maybe it's has ended.");
		}

		response.setContentType("image/png");
		response.setHeader("Pragma", "no-cache");
		response.setHeader("Cache-Control", "no-cache");
		response.setDateHeader("Expires", 0);
		response.setContentLength(chartInfo.getDiagramBytes().length);
		response.getOutputStream().write(chartInfo.getDiagramBytes());
		response.getOutputStream().flush();

	}

	@RequestMapping(value = { "/{processInstanceId}/diagram/{activityId}" }, method = RequestMethod.GET)
	public void diagramActivity(HttpServletRequest request,
			HttpServletResponse response,
			@PathVariable("processInstanceId") String processInstanceId,
			@PathVariable("activityId") String activityId) throws Exception {

		Map<String, ChartInfo> allChartInfos = processMonitorChartInfoHelper
				.getMonitorChartInfo(processInstanceId);
		ChartInfo chartInfo = allChartInfos.get(processInstanceId);
		if (chartInfo == null) {
			throw new ProcessException("Can not get process instance ["
					+ processInstanceId + "] chart info.");
		}
		if (!chartInfo.getTaskDefinitionKeyType().containsKey(activityId)) {
			throw new ProcessException("Can not get activity type ["
					+ activityId + "].");
		}
		
		// FIXME: Dirty code, but because BPMN activity type isn't change frequently.
		ActivitiAccessor activitiAccessor = ActivitiAccessor.retrieveActivitiAccessorImpl(
				processMonitorChartInfoHelper.getWorkflowAccessor(), ActivitiAccessor.class);
		if (chartInfo.getTaskDefinitionKeyType().get(activityId).equals("userTask")) {
			// User task
			List<HistoricTaskInstance> historicTaskList = null;
			historicTaskList = activitiAccessor.getHistoryService()
					.createHistoricTaskInstanceQuery()
					.processInstanceId(processInstanceId)
					.taskDefinitionKey(activityId).list();
			if (historicTaskList == null || historicTaskList.isEmpty()) {
				// Can not find task informations and throws error.
				response.setContentType("application/json");
				JSONWriter jsonWriter = new JSONWriter(response.getWriter());
				jsonWriter.object().endObject();
				response.getWriter().flush();
				return;
			} else {
				// Return historic task information
				response.setContentType("application/json");
				JSONWriter jsonWriter = new JSONWriter(response.getWriter());
				jsonWriter
						.object()
						.key("taskExeMan")
						.value(historicTaskList.get(0).getAssignee())
						.key("taskCompleteTime")
						.value(historicTaskList.get(0).getEndTime() == null ? "Running..."
								: df.format(historicTaskList.get(0)
										.getEndTime()))
						.key("taskInitialTime")
						.value(historicTaskList.get(0).getStartTime() == null ? ""
								: df.format(historicTaskList.get(0)
										.getStartTime()))
						.key("activitiDefineId").value(activityId)
						.endObject();
				response.getWriter().flush();
				return;
			}
		} else if (chartInfo.getTaskDefinitionKeyType().get(activityId).equals("callActivity")) {
			
		} else if (chartInfo.getTaskDefinitionKeyType().get(activityId).equals("subProcess")) {
			
		}
		
		// un-interest type, so return.
		return;

	}

}
