import React, { useState } from 'react';
import { ChevronDown, ChevronRight, BrainCircuit } from 'lucide-react'; // 你的项目已经安装了 lucide-react

interface AIResponseProps {
  content: string;
}

export const AIResponse: React.FC<AIResponseProps> = ({ content }) => {
  const [isExpanded, setIsExpanded] = useState(false);

  // 解析 <think> 标签的逻辑
  const parseContent = (text: string) => {
    // 兼容 <think> 和 <思考>，以及各种大小写情况
    const regex = /<(think|思考)>([\s\S]*?)<\/(think|思考)>/i;
    const match = text.match(regex);
    
    if (match) {
      return {
        thought: match[2].trim(),
        cleanContent: text.replace(regex, '').trim()
      };
    }
    return { thought: null, cleanContent: text };
  };

  const { thought, cleanContent } = parseContent(content);

  // 如果没有思考内容，直接返回普通文本
  if (!thought) {
    return <p className="whitespace-pre-wrap">{content}</p>;
  }

  return (
    <div className="w-full">
      {/* 思考过程折叠卡片 */}
      <div className="mb-3 rounded-xl border border-purple-200/60 bg-white/60 overflow-hidden">
        <button 
          onClick={() => setIsExpanded(!isExpanded)}
          className="w-full flex items-center gap-2 px-3 py-2 text-xs font-medium text-purple-800 bg-purple-50/50 hover:bg-purple-100/50 transition-colors"
        >
          {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          <BrainCircuit size={14} className="text-purple-600"/>
          {isExpanded ? '深度思考过程' : '点击查看思考过程'}
        </button>
        
        {isExpanded && (
          <div className="p-3 text-xs text-gray-600 font-mono bg-white/40 border-t border-purple-100/50 whitespace-pre-wrap leading-relaxed">
            {thought}
          </div>
        )}
      </div>

      {/* 正式回复内容 */}
      <p className="whitespace-pre-wrap leading-relaxed">
        {cleanContent}
      </p>
    </div>
  );
};